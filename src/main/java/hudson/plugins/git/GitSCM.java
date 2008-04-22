package hudson.plugins.git;

import hudson.*;
import hudson.FilePath.FileCallable;
import hudson.model.*;
import hudson.plugins.git.browser.GitWeb;
import hudson.remoting.VirtualChannel;
import hudson.scm.ChangeLogParser;
import hudson.scm.RepositoryBrowsers;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.util.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import javax.sound.midi.MidiDevice.Info;

import java.io.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Git SCM.
 * 
 * @author Nigel Magnay
 */
public class GitSCM extends SCM implements Serializable {
    /**
     * Source repository URL from which we pull.
     */
    private final String source;

    /**
     * In-repository branch to follow. Null indicates "master". 
     */
    private final String branch;
    
    private GitWeb browser;

    @DataBoundConstructor
    public GitSCM(String source, String branch, GitWeb browser) {
        this.source = source;

        // normalization
        branch = Util.fixEmpty(branch);
        
        this.branch = branch;
        this.browser = browser;
    }

    /**
     * Gets the source repository path.
     * Either URL or local file path.
     */
    public String getSource() {
        return source;
    }

    /**
     * In-repository branch to follow. Null indicates "default".
     */
    public String getBranch() {
        return branch==null?"":branch;
    }

    @Override
    public GitWeb getBrowser() {
        return browser;
    }
    
    @Override
    public boolean pollChanges(AbstractProject project, Launcher launcher, FilePath workspace, TaskListener listener) throws IOException, InterruptedException {
    	
    	IGitAPI git = new GitAPI(getDescriptor().getGitExe(), launcher, workspace, listener);
    	
    	listener.getLogger().println("Poll for changes");
    	
    	if(git.hasGitRepo())
    	{
    		// Repo is there - do a fetch
    		listener.getLogger().println("Update repository");
	    	// return true if there are changes, false if not
	    	git.fetch();
	    	
	    	// Find out if there are any changes from there to now
	    	return branchesThatNeedBuilding(launcher, workspace, listener).size() > 0;
    	}
    	else
    	{
    		return true;
    		
    	}
    	
       
    }
    
    private Collection<Branch> filterBranches(Collection<Branch> branches)
    {
    	if( this.branch == null || this.branch.length() == 0 )
    		return branches;
    	Set<Branch> interesting = new HashSet<Branch>(branches);
    	for(Branch b : branches)
    	{
    		if( !b.getName().equals(this.branch) )
    			interesting.remove(b);
    	}
    	return interesting;
    }

    @Override
    public boolean checkout(AbstractBuild build, Launcher launcher, FilePath workspace, BuildListener listener, File changelogFile) throws IOException, InterruptedException {
        
    	IGitAPI git = new GitAPI(getDescriptor().getGitExe(), launcher, workspace, listener);
    	
        if(git.hasGitRepo())
        {
        	// It's an update
        	
        	listener.getLogger().println("Checkout (update)");
        	
            git.fetch();            
            	
            if( git.hasGitModules())
    		{	
            	git.submoduleUpdate();
    		}     
           
        }
        else
        {
        	listener.getLogger().println("Checkout (clone)");
            git.clone(source);
            if( git.hasGitModules())
    		{	
	            git.submoduleInit();
	            git.submoduleUpdate();
    		}
        }
        
        Set<Branch> toBeBuilt = branchesThatNeedBuilding(launcher, workspace, listener);
        String revToBuild = null;
        
        if( toBeBuilt.size() == 0 )
        {
        	listener.getLogger().println("Nothing to do (no unbuilt branches)");
        	revToBuild = git.revParse("HEAD");
        }
        else
        {
        	revToBuild = toBeBuilt.iterator().next().getSHA1();
        }
        
        git.checkout(revToBuild);
        
        String lastRevWas = whenWasBranchLastBuilt(revToBuild, launcher, workspace, listener);
        
        // Fetch the diffs into the changelog file
        putChangelogDiffsIntoFile(lastRevWas, revToBuild, launcher, workspace, listener, changelogFile);
        
        String buildnumber = "hudson-" + build.getProject().getName() + "-" + build.getNumber();
        git.tag(buildnumber, "Hudson Build #" + build.getNumber());
        
        return true;
    }
    
    
    
    /**
     * Are there any branches that haven't been built?
     * 
     * @return SHA1 id of the branch that requires building, or NULL if none
     * are found.
     * @throws IOException 
     * @throws InterruptedException 
     */
    private Set<Branch> branchesThatNeedBuilding(Launcher launcher, FilePath workspace, TaskListener listener) throws IOException 
    {
    	IGitAPI git = new GitAPI(getDescriptor().getGitExe(), launcher, workspace, listener);
    	
    	// These are the set of things tagged that we have tried to build
    	Set<String> setOfThingsBuilt = new HashSet<String>();
    	Set<Branch> branchesThatNeedBuilding = new HashSet<Branch>();
    	
    	for(Tag tag : git.getTags() )
    	{
    		if( tag.getName().startsWith("hudson") )
    			setOfThingsBuilt.add(tag.getCommitSHA1());
    	}
    	
    	for(Branch branch : filterBranches(git.getBranches()))
    	{
    		if( branch.getName().startsWith("origin/") && !setOfThingsBuilt.contains(branch.getSHA1()) )
    			branchesThatNeedBuilding.add(branch);
    	}
    	
    	return (branchesThatNeedBuilding);
    }
    
    private String whenWasBranchLastBuilt(String branchId, Launcher launcher, FilePath workspace, TaskListener listener) throws IOException 
    {
    	IGitAPI git = new GitAPI(getDescriptor().getGitExe(), launcher, workspace, listener);
    	
    	Set<String> setOfThingsBuilt = new HashSet<String>();
    	
    	for(Tag tag : git.getTags() )
    	{
    		if( tag.getName().startsWith("hudson") )
    			setOfThingsBuilt.add(tag.getCommitSHA1());
    	}
    	
    	while(true)
    	{
    		try
    		{
	    		String rev = git.revParse(branchId);
	    		if( setOfThingsBuilt.contains(rev) )
	    			return rev;
	    		branchId += "^";
    		}
    		catch(GitException ex)
    		{
    			// It's never been built.
    			return null;
    		}
    	}
    	
    	
    }
    
    private void putChangelogDiffsIntoFile(String revFrom, String revTo, Launcher launcher, FilePath workspace, TaskListener listener, File changelogFile) throws IOException 
    {
    	IGitAPI git = new GitAPI(getDescriptor().getGitExe(), launcher, workspace, listener);
    	
    	// Delete it to prevent confusion
    	changelogFile.delete();
    	FileOutputStream fos = new FileOutputStream(changelogFile);
    	//fos.write("<data><![CDATA[".getBytes());
    	git.log(revFrom, revTo, fos);
    	//fos.write("]]></data>".getBytes());
    	fos.close();
    }
    

    @Override
    public ChangeLogParser createChangeLogParser() {
        return new GitChangeLogParser();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DescriptorImpl.DESCRIPTOR;
    }

    public static final class DescriptorImpl extends SCMDescriptor<GitSCM> {
        public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

        private String gitExe;

        private  DescriptorImpl() {
            super(GitSCM.class, GitWeb.class);
            load();
        }

        public String getDisplayName() {
            return "Git";
        }

        /**
         * Path to git executable.
         */
        public String getGitExe() {
            if(gitExe==null) return "git";
            return gitExe;
        }

        public SCM newInstance(StaplerRequest req) throws FormException {
            return new GitSCM(
                    req.getParameter("git.source"),
                    req.getParameter("git.branch"),
                    RepositoryBrowsers.createInstance(GitWeb.class, req, "git.browser"));
        }

        public boolean configure(StaplerRequest req) throws FormException {
            gitExe = req.getParameter("git.gitExe");
            save();
            return true;
        }

        public void doGitExeCheck(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
            new FormFieldValidator.Executable(req,rsp) {
                protected void checkExecutable(File exe) throws IOException, ServletException {
                    ByteBuffer baos = new ByteBuffer();
                    try {
                        Proc proc = Hudson.getInstance().createLauncher(TaskListener.NULL).launch(
                                new String[]{getGitExe(), "--version"}, new String[0], baos, null);
                        proc.join();

                        String result = baos.toString();
                        
                        ok();
                        
                    } catch (Exception e) {
                    	error("Unable to check git version");
                    } 
                    
                }
            }.process();
        }
    }


    private static final long serialVersionUID = 1L;

	
}

package org.jenkinsci.plugins.mavenrepocleaner;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.maven.AbstractMavenProject;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import jenkins.MasterToSlaveFileCallable;
import net.sf.json.JSONObject;

/**
 * @author: <a hef="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class MavenRepoCleanerPostBuildTask extends Recorder {
    private static final int DEFAULT_GRACE_PERIOD = 7;

	private int gracePeriodInDays;
	
    @DataBoundConstructor
	public MavenRepoCleanerPostBuildTask(int gracePeriodInDays) {
    	this.gracePeriodInDays = gracePeriodInDays;
    }
    
	/**
	 * @return the gracePeriodInDays
	 */
	public int getGracePeriodInDays() {
		return gracePeriodInDays;
	}
	
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

        final long started = build.getTimeInMillis();
        AbstractProject<?,?> project = build.getProject();
        MavenRepoCleanerProperty mrcp = project.getProperty(MavenRepoCleanerProperty.class);
        long gracePeriodInMillis = gracePeriodInDays * 24 * 60 * 60 * 1000;
        long keepTimeStamp = Math.max(0, started - gracePeriodInMillis);
        
        FilePath.FileCallable<Collection<String>> cleanup = new FileCallableImpl(keepTimeStamp);
        Collection<String> removed = build.getWorkspace().child(".repository").act(cleanup);
        if (removed.size() > 0) {
            listener.getLogger().println( removed.size() + " unused artifacts removed from private maven repository" );
        }
        return true;
    }
    
    @Override
	public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            super(MavenRepoCleanerPostBuildTask.class);
        }

        @Override
        public String getDisplayName() {
            return "Cleanup maven repository for unused artifacts";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return FreeStyleProject.class.isAssignableFrom(jobType)
                    || AbstractMavenProject.class.isAssignableFrom(jobType);
        }
        
        public FormValidation doCheckGracePeriodInDays(@QueryParameter int gracePeriodInDays) {
        	if (gracePeriodInDays < 0) {
        		return FormValidation.error("Must be a positive value.");
        	}
        	return FormValidation.ok();
        }
        
        @Override
        public Publisher newInstance(StaplerRequest aReq, JSONObject formData)
        		throws hudson.model.Descriptor.FormException {
        	
            if (!formData.has("gracePeriodInDays")) {
            	formData.put("gracePeriodInDays", DEFAULT_GRACE_PERIOD);
            }
        	return super.newInstance(aReq, formData);
        }
    }
    
    private static class FileCallableImpl extends MasterToSlaveFileCallable<Collection<String>> {
        private final long keepTimestamp;
        public FileCallableImpl(long keepTimestamp) {
            this.keepTimestamp = keepTimestamp;
        }
        @Override
		public Collection<String> invoke(File repository, VirtualChannel channel) throws IOException, InterruptedException {
            return new RepositoryCleaner(keepTimestamp).clean(repository);
        }
    }
}

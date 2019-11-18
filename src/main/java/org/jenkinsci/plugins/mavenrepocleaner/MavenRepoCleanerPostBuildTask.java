package org.jenkinsci.plugins.mavenrepocleaner;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.maven.AbstractMavenProject;
import hudson.model.AbstractProject;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import jenkins.MasterToSlaveFileCallable;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

/**
 * @author: <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class MavenRepoCleanerPostBuildTask extends Recorder implements SimpleBuildStep {

	private int gracePeriodInDays;
	private String changingArtifactPatterns;
	private int changingArtifactMaxAgeInHours;

    @DataBoundConstructor
	public MavenRepoCleanerPostBuildTask() {
    	setGracePeriodInDays(DescriptorImpl.DEFAULT_GRACE_PERIOD);
    	setChangingArtifactPatterns(DescriptorImpl.DEFAULT_CHANGING_ARTIFACTS_PATTERN);
    	setChangingArtifactMaxAgeInHours(DescriptorImpl.DEFAULT_CHANGING_ARTIFACTS_MAX_AGE_IN_HOURS);
    }

    @DataBoundSetter
	public void setGracePeriodInDays(int gracePeriodInDays) {
		this.gracePeriodInDays = gracePeriodInDays;
	}

    @DataBoundSetter
    public void setChangingArtifactPatterns(String changingArtifactPatterns) {
		this.changingArtifactPatterns = changingArtifactPatterns;
    }

    @DataBoundSetter
	public void setChangingArtifactMaxAgeInHours(int changingArtifactMaxAgeInHours) {
		this.changingArtifactMaxAgeInHours = changingArtifactMaxAgeInHours;
	}

	public int getGracePeriodInDays() {
		return gracePeriodInDays;
	}

	public String getChangingArtifactPatterns() {
		return Util.fixNull(changingArtifactPatterns);
	}

	public int getChangingArtifactMaxAgeInHours() {
		return changingArtifactMaxAgeInHours;
	}

	@Override
	public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener)
			throws InterruptedException, IOException {
    	try {
    		final long started = build.getTimeInMillis();
    		long gracePeriodInMillis = gracePeriodInDays * 24 * 60 * 60 * 1000L;
    		long keepTimeStamp = Math.max(0, started - gracePeriodInMillis);

    		FileCallableImpl cleanup = new FileCallableImpl(keepTimeStamp);
    		Pattern[] patterns = compile(tokenize(getChangingArtifactPatterns()));
    		cleanup.setChangingPatterns(patterns);
    		cleanup.setChangingArtifactMaxAgeInHours(getChangingArtifactMaxAgeInHours());
    		Collection<String> removed = workspace.child(".repository").act(cleanup);
    		if (removed.size() > 0) {
    			listener.getLogger().println(removed.size() + " unused artifacts removed from private maven repository");
    		}
    	} catch (Exception ex) {
    		ex.printStackTrace(listener.error("Error during Maven repository cleanup"));
    	}
    }

	private Pattern[] compile(String[] changingArtifactPatterns) throws PatternSyntaxException {
		Pattern[] result = new Pattern[changingArtifactPatterns.length];
		int i = 0;
		for (String pattern : changingArtifactPatterns) {
			result[i++] = Pattern.compile(pattern);
		}
		return result;
	}

    @Override
	public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    @Symbol("cleanMavenRepo")
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
    	
        public static final int DEFAULT_GRACE_PERIOD = 7;
        public static final String DEFAULT_CHANGING_ARTIFACTS_PATTERN = ".+-SNAPSHOT";
        public static final int DEFAULT_CHANGING_ARTIFACTS_MAX_AGE_IN_HOURS = -1;

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

        public FormValidation doCheckChangingArtifactPatterns(@QueryParameter String changingArtifactPatterns) {
        	for (String regex : tokenize(changingArtifactPatterns)) {
        		try {
        			Pattern.compile(regex);
        		} catch (PatternSyntaxException ex) {
        			return FormValidation.error("Not a valid regular expression: " + ex.getMessage());
        		}
			}
        	return FormValidation.ok();
        }

        @Override
        public Publisher newInstance(StaplerRequest aReq, JSONObject formData)
        		throws hudson.model.Descriptor.FormException {

            if (!formData.has("gracePeriodInDays")) {
            	formData.put("gracePeriodInDays", DEFAULT_GRACE_PERIOD);
            }
            if (!formData.has("changingArtifactPatterns")) {
            	formData.put("changingArtifactPatterns", DEFAULT_CHANGING_ARTIFACTS_PATTERN);
            }
            if (!formData.has("changingArtifactMaxAgeInHours")) {
            	formData.put("changingArtifactMaxAgeInHours", DEFAULT_CHANGING_ARTIFACTS_MAX_AGE_IN_HOURS);
            }
        	return super.newInstance(aReq, formData);
        }
    }

    private static class FileCallableImpl extends MasterToSlaveFileCallable<Collection<String>> {
        private final long keepTimestamp;
		private Pattern[] changingArtifactPatterns;
		private int changingArtifactMaxAgeInHours;

        public FileCallableImpl(long keepTimestamp) {
            this.keepTimestamp = keepTimestamp;
        }

        public void setChangingPatterns(Pattern[] changingArtifactPatterns) {
        	this.changingArtifactPatterns = changingArtifactPatterns;
        }

		public void setChangingArtifactMaxAgeInHours(int changingArtifactMaxAgeInHours) {
			this.changingArtifactMaxAgeInHours = changingArtifactMaxAgeInHours;
		}

		@Override
		public Collection<String> invoke(File repository, VirtualChannel channel) throws IOException, InterruptedException {
            return new RepositoryCleaner(keepTimestamp, changingArtifactPatterns, changingArtifactMaxAgeInHours).clean(repository);
        }
    }

	public static String[] tokenize(String string) {
		return string.split("\\s+");
	}
}

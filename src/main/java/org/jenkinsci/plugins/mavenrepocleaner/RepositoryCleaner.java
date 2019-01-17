package org.jenkinsci.plugins.mavenrepocleaner;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.DirectoryWalker;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.M2GavCalculator;

import hudson.os.PosixAPI;
import jnr.posix.FileStat;

/**
 * Hello world!
 *
 */
public class RepositoryCleaner extends DirectoryWalker
{
    private M2GavCalculator gavCalculator = new M2GavCalculator();
    private long olderThan;
    private String root;
	private Pattern[] changingArtifactPatterns;
	private long changingArtifactMaxAgeInS;
	private Date today;
	private long startTimeInS;

    public RepositoryCleaner(long timestamp, Pattern[] changingArtifactPatterns, int changingArtifactMaxAgeInHours) {
        this.olderThan = timestamp / 1000;
        this.changingArtifactPatterns = changingArtifactPatterns;
        this.changingArtifactMaxAgeInS = changingArtifactMaxAgeInHours * 60 * 60;
    }

	public Collection<String> clean(File repository) throws IOException {
        this.root = repository.getAbsolutePath();
        this.startTimeInS = System.currentTimeMillis() / 1000;
        Collection<String> result = new ArrayList<String>();
        walk(repository, result);
        return result;
    }

    @Override
	protected final void handleDirectoryStart(File directory, int depth, Collection results) throws IOException {

    	if (directory == null) {
    		return;
    	}

        for (File file : directory.listFiles()) {
            if (file.isDirectory()) continue;
            String fileName = file.getName();

            if (fileName.endsWith(".sha1") || fileName.endsWith(".md5")) continue;

            String location = file.getAbsolutePath().substring(root.length());
            Gav gav = gavCalculator.pathToGav(location);
            if (gav == null) continue; // Not an artifact

            olderThan(file, gav, results);
        }

        if ( directory.listFiles(new MetadataFileFilter()).length == 0 ) {
            for (File file : directory.listFiles()) {
                file.delete();
            }
            directory.delete();
        }
    }

    private void olderThan(File file, Gav artifact, Collection results) {
        FileStat fs = PosixAPI.jnr().lstat(file.getPath());
        long lastAccessTime = fs.atime();
        if (lastAccessTime < olderThan || expiredChangingArtifact(artifact, fs)) {
            // This artifact hasn't been accessed during build or is expired
            clean(file, artifact, results);
        }
    }

	private boolean expiredChangingArtifact(Gav artifact, FileStat fs) {
		if (changingArtifactMaxAgeInS < 0) {
			return false;
		}

		if (isChangingArtifactVersion(artifact.getVersion())) {
			long mtime = fs.mtime();
			if (mtime + changingArtifactMaxAgeInS  < startTimeInS) {
				return true;
			}
		}
		return false;
	}

	private boolean isChangingArtifactVersion(String version) {
		for (Pattern pattern : changingArtifactPatterns) {
			Matcher matcher = pattern.matcher(version);
			if (matcher.matches()) {
				return true;
			}
		}
		return false;
	}

	private void clean(File file, Gav artifact, Collection results) {
        File directory = file.getParentFile();
        String fineName = gavCalculator.calculateArtifactName(artifact);
        new File(directory, fineName + ".md5").delete();
        new File(directory, fineName + ".sha1").delete();
        file.delete();
        results.add(gavCalculator.gavToPath(artifact));
    }

    private static class MetadataFileFilter implements FileFilter {

        private final List<String> metadata =
            Arrays.asList(new String[] {
            		"_maven.repositories",
            		"_remote.repositories",
            		"resolver-status.properties",
    		});

        @Override
		public boolean accept(File file) {
        	String name = file.getName();
        	if (name.startsWith("maven-metadata") && name.contains(".xml")) {
        		return false;
        	}
            return !metadata.contains(name);
        }
    }
}


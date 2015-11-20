package org.jenkins.release.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.IOUtil;

/**
 * Goal which prints out the dependencies, versions & SCM links
 */
@Mojo(name = "list", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class ReleaseComparisonToolMojo extends AbstractMojo {
    /**
     * Location of the file.
     */
    @Parameter(property = "jenkinsWar", required = true)
    private File jenkinsWar;

    /**
     * Location of the previous file.
     */
    @Parameter(property = "previousWar", required = false)
    private File previousWar;

    /**
     * Location of the file.
     */
    @Parameter(property = "jenkinsWar", required = false)
    private File tmpDir = createTempDir();

    private File createTempDir() {
        try {
            return Files.createTempDirectory("jenkins_plugin_diff").toFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Writer sysOut = new OutputStreamWriter(System.out) {
        @Override
        public void write(String str) throws java.io.IOException {
            super.write(str);
            flush();
        }
    };

    Writer debugOut = new BufferedWriter(sysOut) {
        @Override
        public void write(String str) throws IOException {
            // TODO when maven debug enabled?
        }
    };

    private MavenXpp3Reader reader = new MavenXpp3Reader();

    /**
     * Holds information about a specific version commit
     */
    public static class PluginVersionCommitInfo implements Comparable<PluginVersionCommitInfo> {
        File dir;
        Model model;
        String repo;
        String commitId;
        String version;

        public PluginVersionCommitInfo(Model model, File dir, String commitId) {
            this.model = model;
            this.dir = dir;
            this.commitId = commitId;
        }

        public int compareTo(PluginVersionCommitInfo o) {
            return model.getArtifactId().compareTo(o.model.getArtifactId());
        }
    }

    public static class PluginVersionCommitDiff {
        PluginVersionCommitInfo from;
        PluginVersionCommitInfo to;

        public PluginVersionCommitDiff(PluginVersionCommitInfo from, PluginVersionCommitInfo to) {
            super();
            this.from = from;
            this.to = to;
        }
    }

    public void execute() throws MojoExecutionException {
        try {
            getPluginVersionCommitInfo(tmpDir, jenkinsWar);
        } catch (Exception e) {
            throw new MojoExecutionException("Error executing Mojo: " + e.getMessage(), e);
        }
    }

    public Set<PluginVersionCommitInfo> getPluginVersionCommitInfo(File tmpDir, File jenkinsWar) throws Exception {
        Set<PluginVersionCommitInfo> infos = new TreeSet<PluginVersionCommitInfo>();

        JarFile f = new JarFile(jenkinsWar);

        for (Enumeration<JarEntry> entries = f.entries(); entries.hasMoreElements();) {
            JarEntry e = entries.nextElement();
            String name = e.getName();
            if ((name.startsWith("WEB-INF/plugins") || e.getName().startsWith("WEB-INF/optional-plugins"))
                    && (name.endsWith(".hpi") || name.endsWith(".jpi"))) {
                PluginVersionCommitInfo info = processJarEntry(tmpDir, f, e);
                if (info != null) {
                    infos.add(info);
                }
            }
        }

        return infos;
    }

    /**
     * Read a jar entry, find the POM, get the source location, version
     * information
     */
    private PluginVersionCommitInfo processJarEntry(File tmpDir, JarFile jenkinsWar, JarEntry bundledPluginEntry)
            throws Exception {
        debug("Processing: " + bundledPluginEntry.getName());
        InputStream in = jenkinsWar.getInputStream(bundledPluginEntry);
        byte[] hpi = IOUtil.toByteArray(in);

        IOUtil.copy(hpi, new FileOutputStream(new File(tmpDir, bundledPluginEntry.getName().replaceAll(".*/", ""))));
        JarInputStream rdr = new JarInputStream(new ByteArrayInputStream(hpi));
        try {
            JarEntry pluginFile;
            int read = 0;
            byte[] buf = new byte[1024];
            ByteArrayOutputStream buff = new ByteArrayOutputStream();
            while ((pluginFile = rdr.getNextJarEntry()) != null) {
                if (pluginFile.getName().contains("pom.xml")) {
                    buff.reset();
                    while ((read = rdr.read(buf)) >= 0) {
                        buff.write(buf, 0, read);
                    }

                    return processPomFile(tmpDir, bundledPluginEntry, buff.toByteArray());
                }
            }
        } finally {
            try {
                rdr.close();
            } catch (Exception ex) {
                // ignore this one
            }
        }

        return null;
    }

    /**
     * Processes a pom file found in a plugin
     */
    private PluginVersionCommitInfo processPomFile(File tmpDir, JarEntry bundledPluginEntry, byte[] pomContents)
            throws Exception {
        Model model = reader.read(new ByteArrayInputStream(pomContents));
        if (model == null) {
            debug("NO MODEL FOR: " + bundledPluginEntry.getName());
            return null;
        }
        if (getVersion(model) == null) {
            debug("NO VERSION FOR: " + bundledPluginEntry.getName());
            return null;
        }
        if (model.getScm() == null) {
            info("NO SCM FOR: " + bundledPluginEntry.getName());
            return null;
        }

        // MavenProject project = new MavenProject(model);
        String repo = replaceProperties(model, model.getScm().getConnection());

        String version = getVersion(model);

        debug("Version: " + version + ", SCM Connection: " + repo + ", SCM Developer Connection: "
                + replaceProperties(model, model.getScm().getDeveloperConnection()));

        if (repo != null) {
            repo = repo.replace("scm:git:", "");
            exec(tmpDir, null, debugOut, "git", "clone", repo);
            return fetchRepoInfo(tmpDir, repo, version);
        }

        return null;
    }

    private PluginVersionCommitInfo fetchRepoInfo(File tmpDir, String repo, String version) throws Exception {
        File repoDir = new File(tmpDir, repo.replaceAll(".*/([^/]+).git", "$1"));

        if (!repoDir.isDirectory()) {
            info("No repository for: " + repo);
            return null;
        }

        debug("RepoDir: ", repoDir.getAbsolutePath());

        StringWriter commitIds = new StringWriter();
        exec(repoDir, null, commitIds, "sh", "-c", "git log pom.xml|grep ^commit|sed 's/commit //g'");

        for (String commitId : commitIds.toString().split("\n")) {
            StringWriter pomContents = new StringWriter();
            exec(repoDir, null, pomContents, "sh", "-c", "git show " + commitId + ":pom.xml");
            try {
                // might have an error parsing (e.g. bad commit)
                Model model = reader.read(new StringReader(pomContents.toString()));
                if (version.equals(getVersion(model))) {
                    debug("Found version ", version, " at: ", commitId);

                    return new PluginVersionCommitInfo(model, repoDir, commitId);
                }
            } catch (Exception e) {
                debug("WARNING, ERROR PARSING POM FOR: ", repo, "@", commitId, ": ", e.getMessage());
            }
        }

        return null;
    }

    private void info(String... s) {
        try {
            for (String part : s) {
                sysOut.write(part);
            }
            sysOut.write("\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void debug(String... s) {
        try {
            for (String part : s) {
                debugOut.write(part);
            }
            debugOut.write("\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Execute a command, dump output to writer
     */
    private void exec(File cwd, Map<String, String> environment, Writer out, String... cmd) {
        debug("EXEC: " + Arrays.toString(cmd));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);

            // stderr -> stdout
            pb.redirectErrorStream(true);

            if (environment != null) {
                Map<String, String> env = pb.environment();
                env.putAll(environment);
            }

            // set cwd
            pb.directory(cwd);

            // go!
            Process p = pb.start();

            // capture out
            BufferedReader psIn = new BufferedReader(new InputStreamReader(p.getInputStream()));

            String line;
            while ((line = psIn.readLine()) != null) {
                out.write(line);
                out.write("\n");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /* get a version from plugin or parent */
    private String getVersion(Model model) {
        if (model.getVersion() != null) {
            return model.getVersion();
        }
        if (model.getParent() != null) {
            return model.getParent().getVersion();
        }
        return null;
    }

    /* replace some known property-type strings; this should be done in a better way */
    private String replaceProperties(Model model, String val) {
        if (val == null) {
            return null;
        }
        if (model.getArtifactId() != null) {
            val = val.replace("${project.artifactId}", model.getArtifactId());
        }
        if (model.getGroupId() != null) {
            val = val.replace("${project.groupId}", model.getGroupId());
        }
        if (getVersion(model) != null) {
            val = val.replace("${project.version}", getVersion(model));
        }
        return val;
    }

    public static void main(String[] args) throws Exception {
        if(System.getProperty("jenkinsWar") == null) {
            System.out.println("Usage: -DjenkinsWar=<path-to-jenkins-war> [-DpreviousJenkinsWar=<path-to-previous-jenkins-war>] [-DversionDiffsOnly=true] ...ReleaseComparisonToolMojo");
        }
        ReleaseComparisonToolMojo mm = new ReleaseComparisonToolMojo();

        Map<String, PluginVersionCommitInfo> newPlugMap = new HashMap<String, ReleaseComparisonToolMojo.PluginVersionCommitInfo>();
        Map<String, PluginVersionCommitInfo> oldPlugMap = new HashMap<String, ReleaseComparisonToolMojo.PluginVersionCommitInfo>();

        List<PluginVersionCommitDiff> newPlugs = new ArrayList<PluginVersionCommitDiff>();
        List<PluginVersionCommitDiff> updatedPlugs = new ArrayList<PluginVersionCommitDiff>();

        if(System.getProperty("previousJenkinsWar") != null) {
            System.out.println("Comparing: " + System.getProperty("jenkinsWar") + " with: " + System.getProperty("previousJenkinsWar"));
        } else {
            System.out.println("Listing plugins for: " + System.getProperty("jenkinsWar"));
        }

        Set<PluginVersionCommitInfo> newIncludedPlugs = mm.getPluginVersionCommitInfo(mm.tmpDir, new File(System.getProperty("jenkinsWar")));

        mm.debug();
        mm.debug();
        mm.debug("NEW plugins: ");
        for (PluginVersionCommitInfo info : newIncludedPlugs) {
            PluginVersionCommitInfo prior = oldPlugMap.get(info.model.getArtifactId());
            if (prior != null) {
                updatedPlugs.add(new PluginVersionCommitDiff(prior, info));
            } else {
                newPlugs.add(new PluginVersionCommitDiff(null, info));
            }
            newPlugMap.put(info.model.getArtifactId(), info);
            mm.debug(info.model.getArtifactId(), "@", info.model.getVersion(), " - ", info.commitId, " @ ",
                    info.dir.getAbsolutePath().toString());
        }

        if(System.getProperty("previousJenkinsWar") != null) {
            Set<PluginVersionCommitInfo> oldIncludedPlugs = mm.getPluginVersionCommitInfo(mm.tmpDir, new File(System.getProperty("previousJenkinsWar")));

            mm.debug();
            mm.debug();
            mm.debug("OLD plugins: ");
            for (PluginVersionCommitInfo info : oldIncludedPlugs) {
                oldPlugMap.put(info.model.getArtifactId(), info);
                mm.debug(info.model.getArtifactId(), "@", info.model.getVersion(), " - ", info.commitId, " @ ",
                        info.dir.getAbsolutePath().toString());
            }

            File diffOut = new File(mm.tmpDir, "diff.txt");
            final FileWriter diffOutWriter = new FileWriter(diffOut);

            Writer out = new BufferedWriter(mm.sysOut) {
                @Override
                public void write(String str) throws IOException {
                    super.write(str);
                    diffOutWriter.write(str);
                }

                @Override
                public void flush() throws IOException {
                    super.flush();
                    diffOutWriter.flush();
                }
            };

            mm.info();
            mm.info("New plugins: ");
            for (PluginVersionCommitDiff diff : newPlugs) {
                mm.info(diff.to.model.getArtifactId(), " ", diff.to.model.getVersion(), " (", diff.to.commitId, ")");
            }

            mm.info();
            mm.info("Updated plugins: ");
            for (PluginVersionCommitDiff diff : updatedPlugs) {
                mm.info(diff.to.model.getArtifactId() + " " + diff.from.model.getVersion(), " (", diff.from.commitId,
                        ")" + " -> " + diff.to.model.getVersion(), " (", diff.to.commitId, ")");
            }

            boolean logDiff = !Boolean.parseBoolean(System.getProperty("versionDiffsOnly"));

            for (PluginVersionCommitDiff diff : newPlugs) {
                // only way to get a full diff vs. an empty repo seems to be to create an empty branch first:
                // See: http://stackoverflow.com/questions/14564034/creating-a-git-diff-from-nothing
                if (logDiff) {
                    mm.exec(diff.to.dir, null, mm.debugOut, "sh", "-c", "git checkout --orphan empty"); // Create orphaned branch
                    mm.exec(diff.to.dir, null, mm.debugOut, "sh", "-c", "git read-tree --empty"); // Remove all files from index
                    mm.exec(diff.to.dir, null, mm.debugOut, "sh", "-c", "git commit --allow-empty -m 'Empty'"); // Initial commit
                    mm.exec(diff.to.dir, null, out, "sh", "-c", "git diff empty.." + diff.to.commitId);
                }
            }

            for (PluginVersionCommitDiff diff : updatedPlugs) {
                // mm.exec(diff.to.dir, null, mm.sysOut, "git", "diff", diff.from.commitId + ".."+diff.to.commitId);
                if (logDiff) {
                    mm.exec(diff.to.dir, null, out, "sh", "-c",
                            "git diff " + diff.from.commitId + ".." + diff.to.commitId + "|sed 's/^-.*//g'");
                }
            }

            mm.sysOut.write("SEE: " + diffOut.getAbsolutePath());

            // ehh
            diffOutWriter.close();
        }
    }
}

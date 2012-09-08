package com.heroku;

import com.heroku.api.App;
import com.heroku.api.HerokuAPI;
import com.heroku.janvil.*;
import hudson.*;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.remoting.VirtualChannel;
import hudson.util.DirScanner;
import hudson.util.FileVisitor;
import hudson.util.FormValidation;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.heroku.HerokuPlugin.Feature.ANVIL;

/*
 * @author Ryan Brainard
 */
public class AnvilPush extends AbstractHerokuBuildStep {

    private final String buildpackUrl;
    private final String buildEnv;
    private final String releaseDesc;
    private final String baseDir;
    private final String globIncludes;
    private final String globExcludes;
    private final boolean useCache;

    @DataBoundConstructor
    public AnvilPush(String apiKey, String appName, String buildpackUrl, String buildEnv, String releaseDesc, String baseDir, String globIncludes, String globExcludes, boolean useCache) {
        super(apiKey, appName);
        this.buildpackUrl = buildpackUrl;
        this.buildEnv = buildEnv;
        this.releaseDesc = releaseDesc;
        this.baseDir = baseDir;
        this.globIncludes = globIncludes;
        this.globExcludes = globExcludes;
        this.useCache = useCache;
    }

    // Overriding and delegating to parent because Jelly only looks at concrete class when rendering views
    @Override
    public String getAppName() {
        return super.getAppName();
    }

    // Overriding and delegating to parent because Jelly only looks at concrete class when rendering views
    @Override
    public String getApiKey() {
        return super.getApiKey();
    }

    public String getBuildpackUrl() {
        return buildpackUrl;
    }

    public String getBuildEnv() {
        return buildEnv;
    }

    public String getReleaseDesc() {
        return releaseDesc;
    }

    public String getBaseDir() {
        return baseDir;
    }

    public String getGlobIncludes() {
        return globIncludes;
    }

    public String getGlobExcludes() {
        return globExcludes;
    }

    public boolean isUseCache() {
        return useCache;
    }

    @Override
    public boolean perform(final AbstractBuild build, final Launcher launcher, final BuildListener listener, HerokuAPI api, final App app) throws IOException, InterruptedException {
        final String userAgent = new JenkinsUserAgentValueProvider().getLocalUserAgent();
        final String userEmail = api.getUserInfo().getEmail();

        return build.getWorkspace().child(baseDir).act(new FilePath.FileCallable<Boolean>() {
            final Janvil janvil = new Janvil(config());

            public Boolean invoke(File dir, VirtualChannel channel) throws IOException, InterruptedException {
                final String slugUrl;
                try {
                    slugUrl = janvil.build(manifest(dir), buildEnv(), buildpackUrl);
                } catch (JanvilBuildException e) {
                    listener.error("A build error occurred: " + e.getExitStatus());
                    return false;
                }

                janvil.release(app.getName(), slugUrl, releaseDesc);

                return true;
            }

            private Manifest manifest(File dir) throws IOException {
                final Manifest manifest = new Manifest(dir);
                new DirScanner.Glob(globIncludes, globExcludes).scan(dir, new FileVisitor() {
                    @Override
                    public void visit(File f, String relativePath) throws IOException {
                        if (f.isFile()) {
                            manifest.add(f);
                        }
                    }
                });
                return manifest;
            }

            private Map<String, String> buildEnv() throws IOException, InterruptedException {
                final Map<String, String> buildEnvMap = MappingConverter.convert(buildEnv);

                // expand with jenkins env vars
                final EnvVars jenkinsEnv = build.getEnvironment(listener);
                for (Map.Entry<String, String> e : buildEnvMap.entrySet()) {
                    e.setValue(jenkinsEnv.expand(e.getValue()));
                }
                return buildEnvMap;
            }

            private Config config() {
                return new Config(getEffectiveApiKey())
                        .setConsumersUserAgent(userAgent)
                        .setReadCacheUrl(useCache)
                        .setWriteCacheUrl(true)
                        .setWriteSlugUrl(false)
                        .setHerokuApp(app.getName())
                        .setHerokuUser(userEmail)
                        .setEventSubscription(eventSubscription());
            }

            private EventSubscription<Janvil.Event> eventSubscription() {
                return new EventSubscription<Janvil.Event>(Janvil.Event.class)
                        .subscribe(Janvil.Event.DIFF_START, new EventSubscription.Subscriber<Janvil.Event>() {
                            public void handle(Janvil.Event event, Object numTotalFiles) {
                                listener.getLogger().println("Workspace contains " + amt(numTotalFiles, "file"));
                            }
                        })
                        .subscribe(Janvil.Event.UPLOADS_START, new EventSubscription.Subscriber<Janvil.Event>() {
                            public void handle(Janvil.Event event, Object numDiffFiles) {
                                if (numDiffFiles == Integer.valueOf(0)) return;
                                listener.getLogger().println("Uploading " + amt(numDiffFiles, "new file") + "...");
                            }
                        })
                        .subscribe(Janvil.Event.UPLOADS_END, new EventSubscription.Subscriber<Janvil.Event>() {
                            public void handle(Janvil.Event event, Object numDiffFiles) {
                                if (numDiffFiles == Integer.valueOf(0)) return;
                                listener.getLogger().println("Upload complete");
                            }
                        })
                        .subscribe(Janvil.Event.BUILD_OUTPUT_LINE, new EventSubscription.Subscriber<Janvil.Event>() {
                            public void handle(Janvil.Event event, Object line) {
                                if (String.valueOf(line).contains("Success, slug is ")) return;
                                listener.getLogger().println(line);
                            }
                        })
                        .subscribe(Janvil.Event.RELEASE_START, new EventSubscription.Subscriber<Janvil.Event>() {
                            public void handle(Janvil.Event event, Object data) {
                                listener.getLogger().println("Releasing to " + app.getName() + "...");
                            }
                        })
                        .subscribe(Janvil.Event.RELEASE_END, new EventSubscription.Subscriber<Janvil.Event>() {
                            public void handle(Janvil.Event event, Object version) {
                                listener.getLogger().println("Push complete, " + version + " | " + app.getWebUrl());
                            }
                        });
            }
        });
    }

    private static String amt(Object qty, String counter) {
        final double num = Double.valueOf(String.valueOf(qty));
        final String s = qty + " " + counter;
        if (num == 1) {
            return s;
        } else {
            return s + "s";
        }
    }

    @Override
    public AnvilPushDescriptor getDescriptor() {
        return (AnvilPushDescriptor) super.getDescriptor();
    }

    @Extension
    public static class AnvilPushDescriptor extends AbstractHerokuBuildStepDescriptor {

        public String getDisplayName() {
            return "Heroku: Push";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return HerokuPlugin.get().hasFeature(ANVIL);
        }

        public FormValidation doCheckBuildpackUrl(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.okWithMarkup(
                        "<a href='https://devcenter.heroku.com/articles/buildpacks' target='_blank'>Buildpack</a> will be auto-detected. Provide a custom URL to override.");
            } else {
                final List<String> allowedSchemes = Arrays.asList("http", "https", "git");
                try {
                    final URI buildpackUrl = new URI(value);
                    if (buildpackUrl.getScheme() == null || !allowedSchemes.contains(buildpackUrl.getScheme())) {
                        return FormValidation.error("Should be of type http:// or git://");
                    }
                    return FormValidation.ok();
                } catch (URISyntaxException e) {
                    return FormValidation.error("Invalid URL format");
                }
            }
        }

        public FormValidation doCheckBuildEnv(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
            try {
                MappingConverter.convert(value);
                return FormValidation.ok();
            } catch (Exception e) {
                return FormValidation.errorWithMarkup(
                        "Error parsing environment variables. " +
                                "Syntax follows that of <a href='http://docs.oracle.com/javase/6/docs/api/java/util/Properties.html#load(java.io.Reader)' target='_blank'> Java Properties files</a>.");
            }
        }

        public FormValidation doCheckGlobIncludes(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
            return FilePath.validateFileMask(project.getSomeWorkspace(), value);
        }

        public FormValidation doCheckGlobExcludes(@AncestorInPath AbstractProject project, @QueryParameter String value) throws IOException {
            return FilePath.validateFileMask(project.getSomeWorkspace(), value);
        }
    }
}

package org.jfrog.hudson.pipeline.declarative.utils;

import com.fasterxml.jackson.databind.JsonNode;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.util.Log;
import org.jfrog.hudson.pipeline.common.Utils;
import org.jfrog.hudson.pipeline.common.executors.GetJFrogPlatformInstancesExecutor;
import org.jfrog.hudson.pipeline.common.types.ArtifactoryServer;
import org.jfrog.hudson.pipeline.common.types.ConanClient;
import org.jfrog.hudson.pipeline.common.types.buildInfo.BuildInfo;
import org.jfrog.hudson.pipeline.declarative.BuildDataFile;
import org.jfrog.hudson.pipeline.declarative.steps.BuildInfoStep;
import org.jfrog.hudson.pipeline.declarative.steps.CreateServerStep;
import org.jfrog.hudson.util.BuildUniqueIdentifierHelper;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.jfrog.hudson.util.ExtractorUtils.createAndGetTempDir;
import static org.jfrog.hudson.util.SerializationUtils.createMapper;

public class DeclarativePipelineUtils {

    static final String PIPELINE_CACHE_DIR_NAME = "artifactory-pipeline-cache";

    /**
     * Create pipeline build data in @tmp/artifactory-pipeline-cache/build-number directory.
     * Used to transfer data between different steps in declarative pipelines.
     *
     * @param rootWs        - Step's root workspace.
     * @param buildNumber   - The build number.
     * @param buildDataFile - The build data file to save.
     * @throws Exception - In case of no write permissions.
     */
    public static void writeBuildDataFile(FilePath rootWs, String buildNumber, BuildDataFile buildDataFile, Log logger) throws Exception {
        createAndGetTempDir(rootWs).act(new CreateBuildDataFileCallable(buildNumber, buildDataFile, logger));
    }

    /**
     * Read pipeline build data from @tmp/artifactory-pipeline-cache/build-number directory.
     * Used to transfer data between different steps in declarative pipelines.
     *
     * @param rootWs      - Step's root workspace.
     * @param buildNumber - The build number.
     * @param stepName    - The step name - One of 'artifactoryMaven', 'mavenDeploy', 'mavenResolve', 'buildInfo' and other declarative pipeline steps.
     * @param stepId      - The step id specified in the pipeline.
     * @throws IOException - In case of no read permissions.
     */
    public static BuildDataFile readBuildDataFile(FilePath rootWs, final String buildNumber, final String stepName, final String stepId) throws IOException, InterruptedException {
        return createAndGetTempDir(rootWs).act(new ReadBuildDataFileCallable(buildNumber, stepName, stepId));
    }

    static String getBuildDataFileName(String stepName, String stepId) {
        return stepName + "_" + Base64.encodeBase64URLSafeString(stepId.getBytes());
    }

    /**
     * Get Artifactory server from global jfrog instances configuration or from previous rtServer{...} scope.
     *
     * @param build          - Step's build.
     * @param rootWs         - Step's root workspace.
     * @param id             - The Artifactory server id. Can be defined from global jfrog instances configuration or from previous rtServer{...} scope.
     * @param throwIfMissing - Throw exception if server is missing.
     * @return Artifactory server.
     */
    public static ArtifactoryServer getArtifactoryServer(Run<?, ?> build, FilePath rootWs, String id, boolean throwIfMissing) throws IOException, InterruptedException {
        String buildNumber = BuildUniqueIdentifierHelper.getBuildNumber(build);
        BuildDataFile buildDataFile = readBuildDataFile(rootWs, buildNumber, CreateServerStep.STEP_NAME, id);
        // If the server has not been configured as part of the declarative pipeline script, get its details from it.
        if (buildDataFile == null) {
            // This server ID has not been configured as part of the declarative pipeline script.
            // Let's get it from the Jenkins configuration.
            GetJFrogPlatformInstancesExecutor getJFrogPlatformInstancesExecutor = new GetJFrogPlatformInstancesExecutor(build, id);
            try {
                getJFrogPlatformInstancesExecutor.execute();
            } catch (GetJFrogPlatformInstancesExecutor.ServerNotFoundException serverNotFound) {
                if (throwIfMissing) {
                    throw serverNotFound;
                }
                return null;
            }
            return getJFrogPlatformInstancesExecutor.getJFrogPlatformInstance().getArtifactoryServer();
        }
        JsonNode jsonNode = buildDataFile.get(CreateServerStep.STEP_NAME);
        ArtifactoryServer server = createMapper().treeToValue(jsonNode, ArtifactoryServer.class);
        JsonNode credentialsId = jsonNode.get("credentialsId");
        if (credentialsId != null && !credentialsId.asText().isEmpty()) {
            server.setCredentialsId(credentialsId.asText());
            return server;
        }
        JsonNode username = jsonNode.get("username");
        if (username != null) {
            server.setUsername(username.asText());
        }
        JsonNode password = jsonNode.get("password");
        if (password != null) {
            server.setPassword(password.asText());
        }

        return server;
    }

    /**
     * Create build info id: <buildname>_<buildnumber>.
     *
     * @param build             - Step's build.
     * @param customBuildName   - Step's custom build name if exist.
     * @param customBuildNumber - Step's custom build number if exist.
     * @return build info id: <buildname>_<buildnumber>.
     */
    public static String createBuildInfoId(Run<?, ?> build, String customBuildName, String customBuildNumber, String project) {
        String projectId = StringUtils.isNotEmpty(project) ? "_" + project : "";
        return StringUtils.defaultIfEmpty(customBuildName, BuildUniqueIdentifierHelper.getBuildName(build)) + "_" +
                StringUtils.defaultIfEmpty(customBuildNumber, BuildUniqueIdentifierHelper.getBuildNumber(build)) + projectId;
    }

    /**
     * Get build info as defined in previous rtBuildInfo{...} scope.
     *
     * @param rootWs            - Step's root workspace.
     * @param build             - Step's build.
     * @param customBuildName   - Step's custom build name if exist.
     * @param customBuildNumber - Step's custom build number if exist.
     * @return build info object as defined in previous rtBuildInfo{...} scope or a new build info.
     */
    public static BuildInfo getBuildInfo(FilePath rootWs, Run<?, ?> build, String customBuildName, String customBuildNumber, String project) throws IOException, InterruptedException {
        String jobBuildNumber = BuildUniqueIdentifierHelper.getBuildNumber(build);
        String buildInfoId = createBuildInfoId(build, customBuildName, customBuildNumber, project);

        BuildDataFile buildDataFile = readBuildDataFile(rootWs, jobBuildNumber, BuildInfoStep.STEP_NAME, buildInfoId);
        if (buildDataFile == null) {
            BuildInfo buildInfo = new BuildInfo(build);
            if (StringUtils.isNotBlank(customBuildName)) {
                buildInfo.setName(customBuildName);
            }
            if (StringUtils.isNotBlank(customBuildNumber)) {
                buildInfo.setNumber(customBuildNumber);
            }
            buildInfo.setProject(project);
            return buildInfo;
        }
        return createMapper().treeToValue(buildDataFile.get(BuildInfoStep.STEP_NAME), BuildInfo.class);
    }

    /**
     * Save build info in @tmp/artifactory-pipeline-cache/build-number folder.
     *
     * @param buildInfo - The build info object to save.
     * @param rootWs    - Step's root workspace.
     * @param build     - Step's build.
     */
    public static void saveBuildInfo(BuildInfo buildInfo, FilePath rootWs, Run<?, ?> build, Log logger) throws Exception {
        String jobBuildNumber = BuildUniqueIdentifierHelper.getBuildNumber(build);
        String buildInfoId = createBuildInfoId(build, buildInfo.getName(), buildInfo.getNumber(), buildInfo.getProject());

        BuildDataFile buildDataFile = new BuildDataFile(BuildInfoStep.STEP_NAME, buildInfoId);
        buildDataFile.putPOJO(buildInfo);
        writeBuildDataFile(rootWs, jobBuildNumber, buildDataFile, logger);
    }

    /**
     * Delete @tmp/artifactory-pipeline-cache/build-number directories older than 1 day.
     */
    static void deleteOldBuildDataDirs(File tmpDir, Log logger) {
        if (!tmpDir.exists()) {
            // Before creation of the @tmp directory
            return;
        }
        File[] buildDataDirs = tmpDir.listFiles(buildDataDir -> {
            long ageInMilliseconds = new Date().getTime() - buildDataDir.lastModified();
            return ageInMilliseconds > TimeUnit.DAYS.toMillis(1);
        });
        if (buildDataDirs == null) {
            logger.error("Failed while attempting to delete old build data dirs. Could not list files in " + tmpDir);
            return;
        }

        Arrays.stream(buildDataDirs).forEach(buildDataDir -> deleteBuildDataDir(buildDataDir, logger));
    }

    /**
     * Delete build data dir from input.
     *
     * @param buildDataDir - The directory to delete
     * @param logger       - The logger
     */
    public static void deleteBuildDataDir(File buildDataDir, Log logger) {
        try {
            FileUtils.deleteDirectory(buildDataDir);
            logger.debug(buildDataDir.getAbsolutePath() + " deleted");
        } catch (IOException e) {
            logger.error("Failed while attempting to delete old build data dir: " + buildDataDir.toString(), e);
        }
    }

    /**
     * Delete build data dir associated with the build number.
     *
     * @param ws          - The workspace
     * @param buildNumber - The build number
     * @param logger      - The logger
     */
    public static void deleteBuildDataDir(FilePath ws, String buildNumber, Log logger) {
        try {
            FilePath buildDataDir = createAndGetTempDir(ws).child(PIPELINE_CACHE_DIR_NAME).child(buildNumber);
            buildDataDir.deleteRecursive();
            logger.debug(buildDataDir.getRemote() + " deleted");
        } catch (IOException | InterruptedException e) {
            logger.error("Failed while attempting to delete build data dir for build number " + buildNumber, e);
        }
    }

    public static ConanClient buildConanClient(String clientId, String buildNumber, String stepName, Launcher launcher, FilePath ws, FilePath rootWs, EnvVars env) throws Exception {
        ConanClient conanClient = new ConanClient();
        conanClient.setUnixAgent(launcher.isUnix());
        BuildDataFile buildDataFile = DeclarativePipelineUtils.readBuildDataFile(rootWs, buildNumber, stepName, clientId);
        if (buildDataFile == null) {
            throw new IOException("Conan client " + clientId + " doesn't exist.");
        }
        String userHome = buildDataFile.get("userHome") == null ? "" : buildDataFile.get("userHome").asText();
        FilePath conanHomeDirectory = Utils.getConanHomeDirectory(userHome, env, launcher, ws);

        conanClient.setUserPath(conanHomeDirectory.getRemote());
        return conanClient;
    }
}

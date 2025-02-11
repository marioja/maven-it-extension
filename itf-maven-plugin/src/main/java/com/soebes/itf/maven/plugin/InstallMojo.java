package com.soebes.itf.maven.plugin;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.soebes.itf.jupiter.maven.ProjectHelper;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.install.ArtifactInstaller;
import org.apache.maven.shared.transfer.repository.RepositoryManager;
import org.codehaus.plexus.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.function.Function.identity;

/**
 * itf-maven-plugin.
 *
 * @author <a href="mailto:khmarbaise@apache.org">Karl Heinz Marbaise</a>
 * @implNote Several parts are taken from the maven-invoker-plugin.
 */
@Mojo(name = "install", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST,
    requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class InstallMojo extends AbstractMojo {
  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  private MavenSession session;

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  private MavenProject project;

  /**
   * This defines the location where the installed artifacts are installed for
   * consumption during the integration tests.
   */
  @Parameter(defaultValue = "${project.basedir}/target/itf-repo", required = true)
  private File itfRepository;

  @Component
  private RepositoryManager repositoryManager;

  @Component
  private ArtifactInstaller installer;

  /**
   * The set of Maven projects in the reactor build.
   */
  @Parameter(defaultValue = "${reactorProjects}", readonly = true)
  private Collection<MavenProject> reactorProjects;

  /**
   * The identifiers of already installed artifacts, used to avoid multiple installation of the same artifact.
   */
  private Collection<String> installedArtifacts;

  /**
   * The identifiers of already copied artifacts, used to avoid multiple installation of the same artifact.
   */
  private Collection<String> copiedArtifacts;

  /**
   * The component used to create artifacts.
   */
  @Component
  private ArtifactFactory artifactFactory;

  /**
   *
   */
  @Parameter(property = "localRepository", required = true, readonly = true)
  private ArtifactRepository localRepository;

  private ProjectBuildingRequest projectBuildingRequest;

  public void execute() throws MojoExecutionException, MojoFailureException {
    createTestRepository();

    installedArtifacts = new HashSet<>();
    copiedArtifacts = new HashSet<>();

    installProjectDependencies(project, reactorProjects);
    installProjectParents(project);
    installProjectArtifacts(project);

    //TODO: We should consider to implement this?
//        installExtraArtifacts( extraArtifacts );

  }


  private void createTestRepository()
      throws MojoExecutionException {

    if (!itfRepository.exists() && !itfRepository.mkdirs()) {
      throw new MojoExecutionException("Failed to create directory: " + itfRepository);
    }
    projectBuildingRequest =
        repositoryManager.setLocalRepositoryBasedir(session.getProjectBuildingRequest(), itfRepository);
  }

  /**
   * Installs the specified artifact to the local repository. Note: This method should only be used for artifacts that
   * originate from the current (reactor) build. Artifacts that have been grabbed from the user's local repository
   * should be installed to the test repository via {@link #copyArtifact(File, Artifact)}.
   *
   * @param file The file associated with the artifact, must not be <code>null</code>. This is in most cases the value
   * of <code>artifact.getFile()</code> with the exception of the main artifact from a project with
   * packaging "pom". Projects with packaging "pom" have no main artifact file. They have however artifact
   * metadata (e.g. site descriptors) which needs to be installed.
   * @param artifact The artifact to install, must not be <code>null</code>.
   * @throws MojoExecutionException If the artifact could not be installed (e.g. has no associated file).
   */
  private void installArtifact(File file, Artifact artifact)
      throws MojoExecutionException {
    try {
      if (file == null) {
        throw new IllegalStateException("Artifact has no associated file: " + artifact.getId());
      }
      if (!file.isFile()) {
        throw new IllegalStateException("Artifact is not fully assembled: " + file);
      }

      if (installedArtifacts.add(artifact.getId())) {
        artifact.setFile(file);
        installer.install(projectBuildingRequest, itfRepository,
            Collections.singletonList(artifact));
      } else {
        getLog().debug("Not re-installing " + artifact + ", " + file);
      }
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to install artifact: " + artifact, e);
    }
  }

  /**
   * Installs the specified artifact to the local repository. This method serves basically the same purpose as
   * {@link #installArtifact(File, Artifact)} but is meant for artifacts that have been resolved
   * from the user's local repository (and not the current build outputs). The subtle difference here is that
   * artifacts from the repository have already undergone transformations and these manipulations should not be redone
   * by the artifact installer. For this reason, this method performs plain copy operations to install the artifacts.
   *
   * @param file The file associated with the artifact, must not be <code>null</code>.
   * @param artifact The artifact to install, must not be <code>null</code>.
   * @throws MojoExecutionException If the artifact could not be installed (e.g. has no associated file).
   */
  private void copyArtifact(File file, Artifact artifact)
      throws MojoExecutionException {
    try {
      if (file == null) {
        throw new IllegalStateException("Artifact has no associated file: " + artifact.getId());
      }
      if (!file.isFile()) {
        throw new IllegalStateException("Artifact is not fully assembled: " + file);
      }

      if (copiedArtifacts.add(artifact.getId())) {
        File destination =
            new File(itfRepository,
                repositoryManager.getPathForLocalArtifact(projectBuildingRequest, artifact));

        getLog().debug("Installing " + file + " to " + destination);

        copyFileIfDifferent(file, destination);

        MetadataUtils.createMetadata(destination, artifact);
      } else {
        getLog().debug("Not re-installing " + artifact + ", " + file);
      }
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to stage artifact: " + artifact, e);
    }
  }

  private void copyFileIfDifferent(File src, File dst)
      throws IOException {
    if (src.lastModified() != dst.lastModified() || src.length() != dst.length()) {
      FileUtils.copyFile(src, dst);
      dst.setLastModified(src.lastModified());
    }
  }

  /**
   * Installs the main artifact and any attached artifacts of the specified project to the local repository.
   *
   * @param mvnProject The project whose artifacts should be installed, must not be <code>null</code>.
   * @throws MojoExecutionException If any artifact could not be installed.
   */
  private void installProjectArtifacts(MavenProject mvnProject)
      throws MojoExecutionException {
    try {
      // Install POM (usually attached as metadata but that happens only as a side effect of the Install Plugin)
      installProjectPom(mvnProject);

      // Install the main project artifact (if the project has one, e.g. has no "pom" packaging)
      Artifact mainArtifact = mvnProject.getArtifact();
      if (mainArtifact.getFile() != null) {
        installArtifact(mainArtifact.getFile(), mainArtifact);
      }

      // Install any attached project artifacts
      Collection<Artifact> attachedArtifacts = mvnProject.getAttachedArtifacts();
      for (Artifact attachedArtifact : attachedArtifacts) {
        installArtifact(attachedArtifact.getFile(), attachedArtifact);
      }
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to install project artifacts: " + mvnProject, e);
    }
  }

  /**
   * Installs the (locally reachable) parent POMs of the specified project to the local repository. The parent POMs
   * from the reactor must be installed or the forked IT builds will fail when using a clean repository.
   *
   * @param mvnProject The project whose parent POMs should be installed, must not be <code>null</code>.
   * @throws MojoExecutionException If any POM could not be installed.
   */
  private void installProjectParents(MavenProject mvnProject)
      throws MojoExecutionException {
    try {
      for (MavenProject parent = mvnProject.getParent(); parent != null; parent = parent.getParent()) {
        if (parent.getFile() == null) {
          copyParentPoms(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
          break;
        }
        installProjectPom(parent);
      }
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to install project parents: " + mvnProject, e);
    }
  }

  /**
   * Installs the POM of the specified project to the local repository.
   *
   * @param mvnProject The project whose POM should be installed, must not be <code>null</code>.
   * @throws MojoExecutionException If the POM could not be installed.
   */
  private void installProjectPom(MavenProject mvnProject)
      throws MojoExecutionException {
    try {
      Artifact pomArtifact = null;
      if ("pom".equals(mvnProject.getPackaging())) {
        pomArtifact = mvnProject.getArtifact();
      }
      if (pomArtifact == null) {
        pomArtifact =
            artifactFactory.createProjectArtifact(mvnProject.getGroupId(), mvnProject.getArtifactId(),
                mvnProject.getVersion());
      }
      installArtifact(mvnProject.getFile(), pomArtifact);
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to install POM: " + mvnProject, e);
    }
  }

  private static final Function<Artifact, String> ArtifactIntoGAV = artifact -> artifact.getGroupId() + ':' + artifact.getArtifactId() + ':' + artifact.getBaseVersion();
  private static final Function<MavenProject, String> ProjectIntoGAV = project -> project.getGroupId() + ':' + project.getArtifactId() + ':' + project.getVersion();

  private static final Predicate<Artifact> isInProjects(Map<String, MavenProject> projects) {
    return s -> projects.containsKey(ArtifactIntoGAV.apply(s));
  }

  /**
   * Installs the dependent projects from the reactor to the local repository. The dependencies on other modules from
   * the reactor must be installed or the forked IT builds will fail when using a clean repository.
   *
   * @param mvnProject The project whose dependent projects should be installed, must not be <code>null</code>.
   * @param reactorProjects The set of projects in the reactor build, must not be <code>null</code>.
   * @throws MojoExecutionException If any dependency could not be installed.
   */
  private void installProjectDependencies(MavenProject mvnProject, Collection<MavenProject> reactorProjects)
      throws MojoExecutionException {

    // ... into dependencies that were resolved from reactor projects ...
    Collection<String> dependencyProjects = new LinkedHashSet<>();
    collectAllProjectReferences(mvnProject, dependencyProjects);

    // index available reactor projects
    Map<String, MavenProject> projects = reactorProjects.stream()
        .collect(Collectors.toMap(ProjectIntoGAV, identity()));

    // group transitive dependencies (even those that don't contribute to the class path like POMs) ...
    // ... and those that were resolved from the (local) repo
    Collection<Artifact> dependencyArtifacts = mvnProject.getArtifacts().stream()
        .filter(isInProjects(projects).negate())
        .collect(Collectors.collectingAndThen(Collectors.toList(), LinkedHashSet::new));

    // install dependencies
    try {
      // copy dependencies that where resolved from the local repo
      for (Artifact artifact : dependencyArtifacts) {
        copyArtifact(artifact);
      }

      // install dependencies that were resolved from the reactor
      for (String projectId : dependencyProjects) {
        MavenProject dependencyProject = projects.get(projectId);
        if (dependencyProject == null) {
          getLog().warn("skip dependencyProject null for projectId=" + projectId);
          continue;
        }
        getLog().info(" *-> " + project.getId());
        installProjectArtifacts(dependencyProject);
        installProjectParents(dependencyProject);
      }
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to install project dependencies: " + mvnProject, e);
    }
  }

  protected void collectAllProjectReferences(MavenProject project, Collection<String> dependencyProjects) {
    for (MavenProject reactorProject : project.getProjectReferences().values()) {
      String projectId =
          reactorProject.getGroupId() + ':' + reactorProject.getArtifactId() + ':' + reactorProject.getVersion();
      if (dependencyProjects.add(projectId)) {
        collectAllProjectReferences(reactorProject, dependencyProjects);
      }
    }
  }

  private void copyArtifact(Artifact artifact)
      throws MojoExecutionException {
    copyPoms(artifact);

    Artifact depArtifact =
        artifactFactory.createArtifactWithClassifier(artifact.getGroupId(), artifact.getArtifactId(),
            artifact.getBaseVersion(), artifact.getType(),
            artifact.getClassifier());

    File artifactFile = artifact.getFile();

    copyArtifact(artifactFile, depArtifact);
  }

  private void copyPoms(Artifact artifact)
      throws MojoExecutionException {
    Artifact pomArtifact =
        artifactFactory.createProjectArtifact(artifact.getGroupId(), artifact.getArtifactId(),
            artifact.getBaseVersion());

    File pomFile = new File(localRepository.getBasedir(), localRepository.pathOf(pomArtifact));

    if (pomFile.isFile()) {
      copyArtifact(pomFile, pomArtifact);
      copyParentPoms(pomFile);
    }
  }

  /**
   * Installs all parent POMs of the specified POM file that are available in the local repository.
   *
   * @param pomFile The path to the POM file whose parents should be installed, must not be <code>null</code>.
   * @throws MojoExecutionException If any (existing) parent POM could not be installed.
   */
  private void copyParentPoms(File pomFile)
      throws MojoExecutionException {
    Model model = ProjectHelper.readProject(pomFile.toPath());
    Parent parent = model.getParent();
    if (parent != null) {
      copyParentPoms(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }
  }

  /**
   * Installs the specified POM and all its parent POMs to the local repository.
   *
   * @param groupId The group id of the POM which should be installed, must not be <code>null</code>.
   * @param artifactId The artifact id of the POM which should be installed, must not be <code>null</code>.
   * @param version The version of the POM which should be installed, must not be <code>null</code>.
   * @throws MojoExecutionException If any (existing) parent POM could not be installed.
   */
  private void copyParentPoms(String groupId, String artifactId, String version)
      throws MojoExecutionException {
    Artifact pomArtifact = artifactFactory.createProjectArtifact(groupId, artifactId, version);

    if (installedArtifacts.contains(pomArtifact.getId()) || copiedArtifacts.contains(pomArtifact.getId())) {
      getLog().debug("Not re-installing " + pomArtifact);
      return;
    }

    File pomFile = new File(localRepository.getBasedir(), localRepository.pathOf(pomArtifact));
    if (pomFile.isFile()) {
      copyArtifact(pomFile, pomArtifact);
      copyParentPoms(pomFile);
    }
  }


}

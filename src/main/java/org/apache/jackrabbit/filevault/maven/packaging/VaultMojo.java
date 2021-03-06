/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.filevault.maven.packaging;

import static org.codehaus.plexus.archiver.util.DefaultFileSet.fileSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.jackrabbit.vault.fs.api.PathFilterSet;
import org.apache.jackrabbit.vault.fs.config.ConfigurationException;
import org.apache.jackrabbit.vault.util.Constants;
import org.apache.jackrabbit.vault.util.PlatformNameFormat;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.codehaus.plexus.archiver.FileSet;
import org.codehaus.plexus.archiver.jar.ManifestException;
import org.codehaus.plexus.archiver.util.DefaultFileSet;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.jetbrains.annotations.NotNull;

/** Builds a content package. */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class VaultMojo extends AbstractSourceAndMetadataPackageMojo {

    private static final String PACKAGE_TYPE = "zip";

    static final String PACKAGE_EXT = "." + PACKAGE_TYPE;

    private static final Collection<File> STATIC_META_INF_FILES = Arrays.asList(new File(Constants.META_DIR, Constants.CONFIG_XML),
            new File(Constants.META_DIR, Constants.SETTINGS_XML));

    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    /** Set to {@code true} to fail the build in case of files are being contained in the {@code jcrRootSourceDirectory} which are not
     * covered by the filter rules and therefore would not end up in the package. */
    @Parameter(property = "vault.failOnUncoveredSourceFiles", required = true, defaultValue = "false")
    private boolean failOnUncoveredSourceFiles;

    /** Set to {@code false} to not fail the build in case of files/folders being added to the resulting package more than once. Usually
     * this indicates overlapping with embedded files or overlapping filter rules. */
    @Parameter(property = "vault.failOnDuplicateEntries", required = true, defaultValue = "true")
    private boolean failOnDuplicateEntries;

    /** The name of the generated package ZIP file without the ".zip" file extension. */
    @Parameter(property = "vault.finalName", defaultValue = "${project.build.finalName}", required = true)
    private String finalName;

    /** Directory in which the built content package will be output. */
    @Parameter(property = "vault.outputDirectory", defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;

    @Parameter(property = "vault.enableMetaInfFiltering", defaultValue = "false")
    private boolean enableMetaInfFiltering;

    @Parameter(property = "vault.enableJcrRootFiltering", defaultValue = "false") 
    private boolean enableJcrRootFiltering;

    /**
     * <p>
     * Set of delimiters for expressions to filter within the resources. These delimiters are specified in the form 'beginToken*endToken'.
     * If no '*' is given, the delimiter is assumed to be the same for start and end.
     * </p>
     * <p>
     * So, the default filtering delimiters might be specified as:
     * </p>
     * 
     * <pre>
     * &lt;delimiters&gt;
     *   &lt;delimiter&gt;${*}&lt;/delimiter&gt;
     *   &lt;delimiter&gt;@&lt;/delimiter&gt;
     * &lt;/delimiters&gt;
     * </pre>
     * <p>
     * Since the '@' delimiter is the same on both ends, we don't need to specify '@*@' (though we can).
     * </p>
     *
     * @since 1.1.0 */
    @Parameter(property = "vault.delimiters")
    private LinkedHashSet<String> delimiters;

    /** Use default delimiters in addition to custom delimiters, if any.
     *
     * @since 1.1.0 
     */
    @Parameter(property = "vault.useDefaultDelimiters", defaultValue = "true")
    private boolean useDefaultDelimiters;

    /** The character encoding scheme to be applied when filtering resources. */
    @Parameter(property = "vault.resourceEncoding", defaultValue = "${project.build.sourceEncoding}")
    private String resourceEncoding;

    /** Filters (path to  property files) to include during the filtering of resources. The default value is the filter list under build
     * specifying a filter will override the filter list under build.
     * 
     * @since 1.1.0 */
    @Parameter(property="vault.filters")
    private List<String> filterFiles;

    /** Expression preceded with this String won't be interpolated. <code>\${foo}</code> will be replaced with <code>${foo}</code>.
     *
     * @since 1.1.0 */
    @Parameter(property="vault.escapeString")
    protected String escapeString;

    /** To escape interpolated values with Windows path <code>c:\foo\bar</code> will be replaced with <code>c:\\foo\\bar</code>.
     *
     * @since 1.1.0 */
    @Parameter(property="vault.escapedBackslashesInFilePath", defaultValue = "false")
    private boolean escapedBackslashesInFilePath;

    /** Additional list of file extensions that should not be filtered.
     * (already defined are : jpg, jpeg, gif, bmp, png)
     * @since 1.1.0 */
    @Parameter(property="vault.nonFilteredFileExtensions")
    private List<String> nonFilteredFileExtensions;

    /** Stop searching endToken at the end of line when filtering is applied.
     * 
     * @since 1.1.0 */
    @Parameter(property="vault.supportMultiLineFiltering", defaultValue = "false")
    private boolean supportMultiLineFiltering;

    @Parameter(defaultValue = "${session}", readonly = true, required = false)
    protected MavenSession session;

    /** The archive configuration to use. See <a href="http://maven.apache.org/shared/maven-archiver/index.html">the documentation for Maven
     * Archiver</a>.
     * 
     * All settings related to manifest are not relevant as this gets overwritten by the manifest in
     * {@link AbstractMetadataPackageMojo#workDirectory} */
    @Parameter
    private MavenArchiveConfiguration archive;

    /**
     */
    @Component(role = MavenFileFilter.class, hint = "default")
    private MavenFileFilter mavenFileFilter;

    /**
     */
    @Component(role = MavenResourcesFiltering.class, hint = "default") 
    MavenResourcesFiltering mavenResourcesFiltering;

    /** All file names (relative to the zip root) which are supposed to not get overwritten in the package. The value is the source file. */
    private Map<File, File> protectedFiles = new HashMap<>();

    /** Creates a {@link FileSet} for the archiver
     * 
     * @param directory the directory
     * @param prefix the prefix
     * @return the fileset */
    @NotNull
    protected DefaultFileSet createFileSet(@NotNull File directory, @NotNull String prefix) {
        return createFileSet(directory, prefix, null);
    }

    /** Creates a {@link FileSet} for the archiver
     * 
     * @param directory the directory
     * @param prefix the prefix
     * @param additionalExcludes excludes
     * @return the fileset */
    @NotNull
    protected DefaultFileSet createFileSet(@NotNull File directory, @NotNull String prefix, List<String> additionalExcludes) {
        List<String> excludes = new LinkedList<>(this.excludes);
        if (additionalExcludes != null) {
            excludes.addAll(additionalExcludes);
        }
        DefaultFileSet fileSet = fileSet(directory)
                .prefixed(prefix)
                .includeExclude(null, excludes.toArray(new String[0]))
                .includeEmptyDirs(true);
        fileSet.setUsingDefaultExcludes(addDefaultExcludes);
        return fileSet;
    }

    /**
     * Adds a file to the archiver and optionally applies some filtering.
     * @param mavenResourcesExecution
     * @param archiver
     * @param sourceFile
     * @param destFileName
     * @throws MavenFilteringException in case filtering failed
     */
    protected void addFileToArchive(MavenResourcesExecution mavenResourcesExecution, ContentPackageArchiver archiver, File sourceFile,
            String destFileName) throws MavenFilteringException {
        Path destFile = Paths.get(destFileName);
        if ((destFile.startsWith(Constants.ROOT_DIR) && enableJcrRootFiltering) ||
            (destFile.startsWith(Constants.META_INF) && enableMetaInfFiltering)) {
            getLog().info("Apply filtering to " + sourceFile);
            Resource resource = new Resource();
            resource.setDirectory(sourceFile.getParent());
            resource.setIncludes(Collections.singletonList(sourceFile.getName()));
            resource.setFiltering(true);
            File newTargetDirectory = applyFiltering(destFile.getParent().toString(), mavenResourcesExecution, resource);
            sourceFile = new File(newTargetDirectory, sourceFile.getName());
        }
        getLog().debug("Adding file '" + sourceFile + "' to package at '" + destFileName + "'");
        archiver.addFile(sourceFile, destFileName);

    }

    /**
     * Adds a fileSet to the archiver and optionally applies some filtering.
     * @param mavenResourcesExecution
     * @param archiver
     * @param fileSet
     * @throws MavenFilteringException in case filtering failed
     */
    protected void addFileSetToArchive(MavenResourcesExecution mavenResourcesExecution, ContentPackageArchiver archiver, DefaultFileSet fileSet) throws MavenFilteringException {
        // ignore directories added with no prefix (workDirectory)
        if ((fileSet.getPrefix().startsWith(Constants.ROOT_DIR) && enableJcrRootFiltering) ||
            (fileSet.getPrefix().startsWith(Constants.META_INF) && enableMetaInfFiltering)) {
            
            getLog().info("Apply filtering to FileSet below " + fileSet.getDirectory());
            Resource resource = new Resource();
            resource.setDirectory(fileSet.getDirectory().getPath());
            if (fileSet.getIncludes() != null) {
                resource.setIncludes(Arrays.asList(fileSet.getIncludes()));
            }
            if (fileSet.getExcludes() != null) {
                resource.setExcludes(Arrays.asList(fileSet.getExcludes()));
                // default exclude are managed via mavenResourcesExecution
            }
            resource.setFiltering(true);
            File newTargetDirectory = applyFiltering(fileSet.getPrefix(), mavenResourcesExecution, resource);
            fileSet.setDirectory(newTargetDirectory);
        }
        getLog().debug("Adding fileSet '" + getString(fileSet) + "' to package");
        archiver.addFileSet(fileSet);
    }

    private static String getString(FileSet fileSet) {
        StringBuilder sb = new StringBuilder("FileSet [");
        sb.append("directory=").append(fileSet.getDirectory());
        sb.append(" prefix=").append(fileSet.getPrefix());
        sb.append(" excludes=").append(StringUtils.join(fileSet.getExcludes(), ", "));
        sb.append("]");
        return sb.toString();
    }

    private @NotNull File applyFiltering(String prefix, MavenResourcesExecution mavenResourcesExecution, Resource resource) throws MavenFilteringException {
        File targetPath = new File(project.getBuild().getDirectory(), "filteredFiles");
        mavenResourcesExecution.setOutputDirectory(targetPath);
        targetPath = new File(targetPath, prefix);
        // which path to set as target (is a temporary path)
        getLog().debug("Applying filtering to resource " + resource);
        resource.setTargetPath(targetPath.getPath());
        mavenResourcesExecution.setResources(Collections.singletonList(resource));
        mavenResourcesFiltering.filterResources(mavenResourcesExecution);
        return targetPath;
    }

    private boolean isOverwritingProtectedFile(File zipFile, File sourceFile, boolean isProtected) {
        if (protectedFiles.containsKey(zipFile)) {
            return true;
        }
        if (isProtected) {
            protectedFiles.put(zipFile, sourceFile);
        }
        return false;
    }

    /** @param fileSet
     * @param isProtected
     * @return a map with key = file path in zip and value = absolute source file */
    private Map<File, File> getOverwrittenProtectedFiles(FileSet fileSet, boolean isProtected) {
        // copied from PlexusIoFileResourceCollection.getResources()
        Map<File, File> overwrittenFiles = new HashMap<>();
        final DirectoryScanner ds = new DirectoryScanner();
        final File dir = fileSet.getDirectory();
        ds.setBasedir(dir);
        final String[] inc = fileSet.getIncludes();
        if (inc != null && inc.length > 0) {
            ds.setIncludes(inc);
        }
        final String[] exc = fileSet.getExcludes();
        if (exc != null && exc.length > 0) {
            ds.setExcludes(exc);
        }
        if (fileSet.isUsingDefaultExcludes()) {
            ds.addDefaultExcludes();
        }
        ds.setCaseSensitive(fileSet.isCaseSensitive());
        ds.setFollowSymlinks(false);
        ds.scan();

        String[] files = ds.getIncludedFiles();
        for (String file : files) {
            File zipFileEntry = new File(fileSet.getPrefix() + file);
            File sourceFile = new File(fileSet.getDirectory(), file);
            if (isOverwritingProtectedFile(zipFileEntry, sourceFile, isProtected)) {
                overwrittenFiles.put(zipFileEntry, sourceFile);
            }
        }
        return overwrittenFiles;
    }

    protected MavenResourcesExecution setupMavenResourcesExecution() {
        MavenResourcesExecution mavenResourcesExecution = new MavenResourcesExecution();
        mavenResourcesExecution.setEscapeString(escapeString);
        mavenResourcesExecution.setSupportMultiLineFiltering(supportMultiLineFiltering);
        mavenResourcesExecution.setMavenProject(project);

        // if these are NOT set, just use the defaults, which are '${*}' and '@'.
        mavenResourcesExecution.setDelimiters(delimiters, useDefaultDelimiters);

        if (nonFilteredFileExtensions != null) {
            mavenResourcesExecution.setNonFilteredFileExtensions(nonFilteredFileExtensions);
        }

        if (filterFiles == null) {
            filterFiles = project.getBuild().getFilters();
        }
        mavenResourcesExecution.setFilters(filterFiles);
        mavenResourcesExecution.setEscapedBackslashesInFilePath(escapedBackslashesInFilePath);
        mavenResourcesExecution.setMavenSession(this.session);
        mavenResourcesExecution.setEscapeString(this.escapeString);
        mavenResourcesExecution.setSupportMultiLineFiltering(supportMultiLineFiltering);
        mavenResourcesExecution.setAddDefaultExcludes(addDefaultExcludes);
        mavenResourcesExecution.setOverwrite(true);
        mavenResourcesExecution.setUseDefaultFilterWrappers(true);
        return mavenResourcesExecution;
    }

    /** Executes this mojo */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final File finalFile = new File(outputDirectory, finalName + PACKAGE_EXT);

        MavenResourcesExecution mavenResourcesExection = setupMavenResourcesExecution();
        try {
            // find the meta-inf source directory
            File metaInfDirectory = getMetaInfVaultSourceDirectory();
            // find the source directory
            final File jcrSourceDirectory = getJcrSourceDirectory();
            if (jcrSourceDirectory != null) {
                getLog().info("Packaging content from " + jcrSourceDirectory.getPath());
            }
            // retrieve filters
            Filters filters = loadGeneratedFilterFile();
            Map<String, File> embeddedFiles = getEmbeddedFilesMap();

            ContentPackageArchiver contentPackageArchiver = new ContentPackageArchiver();

            // A map with key = relative file in zip and value = absolute source file name)
            Map<File, File> duplicateFiles = new HashMap<>();
            contentPackageArchiver.setIncludeEmptyDirs(true);
            if (metaInfDirectory != null) {
                // first add the metadata from the metaInfDirectory (they should take precedence over the generated ones from workDirectory,
                // except for the filter.xml, which should always come from the work directory)
                DefaultFileSet fileSet = createFileSet(metaInfDirectory, Constants.META_DIR + "/",
                        Collections.singletonList(Constants.FILTER_XML));
                duplicateFiles.putAll(getOverwrittenProtectedFiles(fileSet, true));
                addFileSetToArchive(mavenResourcesExection, contentPackageArchiver, fileSet);
            }
            // then add all files from the workDirectory (they might overlap with the ones from metaInfDirectory, but the duplicates are
            // just ignored in the package)
            DefaultFileSet fileSet = createFileSet(workDirectory, "");
            // issue warning in case of overlaps
            Map<File, File> overwrittenWorkFiles = getOverwrittenProtectedFiles(fileSet, true);
            for (Entry<File, File> entry : overwrittenWorkFiles.entrySet()) {
                String message = "Found duplicate file '" + entry.getKey() + "' from sources '" + protectedFiles.get(entry.getKey())
                        + "' and '" + entry.getValue() + "'.";

                // INFO for the static ones all others warn
                if (STATIC_META_INF_FILES.contains(entry.getKey())) {
                    getLog().info(message);
                } else {
                    getLog().warn(message);
                }
            }
            addFileSetToArchive(mavenResourcesExection, contentPackageArchiver, fileSet);

            // add embedded files
            for (Map.Entry<String, File> entry : embeddedFiles.entrySet()) {
                protectedFiles.put(new File(entry.getKey()), entry.getValue());
                addFileToArchive(mavenResourcesExection, contentPackageArchiver, entry.getValue(), entry.getKey());
            }

            // include content from build only if it exists
            if (jcrSourceDirectory != null && jcrSourceDirectory.exists()) {
                Map<File, File> overwrittenFiles = addSourceDirectory(mavenResourcesExection, contentPackageArchiver, jcrSourceDirectory, filters, embeddedFiles);
                duplicateFiles.putAll(overwrittenFiles);

                if (!duplicateFiles.isEmpty()) {
                    for (Entry<File, File> entry : duplicateFiles.entrySet()) {
                        String message = "Found duplicate file '" + entry.getKey() + "' from sources '" + protectedFiles.get(entry.getKey())
                                + "' and '" + entry.getValue() + "'.";
                        if (failOnDuplicateEntries) {
                            getLog().error(message);
                        } else {
                            getLog().warn(message);
                        }
                    }
                    if (failOnDuplicateEntries) {
                        throw new MojoFailureException(
                                "Found " + duplicateFiles.size() + " duplicate file(s) in content package, see above errors for details.");
                    }
                }

                // check for uncovered files (i.e. files from the source which are not even added to the content package)
                Collection<File> uncoveredFiles = getUncoveredFiles(jcrSourceDirectory, excludes, prefix,
                        contentPackageArchiver.getFiles().keySet());
                if (!uncoveredFiles.isEmpty()) {
                    for (File uncoveredFile : uncoveredFiles) {
                        String message = "File '" + uncoveredFile
                                + "' not covered by a filter rule and therefore not contained in the resulting package";
                        if (failOnUncoveredSourceFiles) {
                            getLog().error(message);
                        } else {
                            getLog().warn(message);
                        }
                    }
                    if (failOnUncoveredSourceFiles) {
                        throw new MojoFailureException("The following files are not covered by a filter rule: \n"
                                + StringUtils.join(uncoveredFiles.iterator(), ",\n"));
                    }
                }
            }

            MavenArchiver mavenArchiver = new MavenArchiver();
            mavenArchiver.setArchiver(contentPackageArchiver);
            mavenArchiver.setOutputFile(finalFile);
            mavenArchiver.configureReproducible(outputTimestamp);
            mavenArchiver.createArchive(null, project, getMavenArchiveConfiguration(getGeneratedManifestFile()));

            // set the file for the project's artifact and ensure the
            // artifact is correctly handled with the "zip" handler
            // (workaround for MNG-1682)
            final Artifact projectArtifact = project.getArtifact();
            projectArtifact.setFile(finalFile);
            projectArtifact.setArtifactHandler(artifactHandlerManager.getArtifactHandler(PACKAGE_TYPE));

        } catch (IllegalStateException | ManifestException | IOException | DependencyResolutionRequiredException | ConfigurationException | MavenFilteringException e) {
            throw new MojoExecutionException(e.toString(), e);
        }
    }

    private Map<File, File> addSourceDirectory(MavenResourcesExecution mavenResourcesExecution, ContentPackageArchiver contentPackageArchiver, File jcrSourceDirectory, Filters filters,
            Map<String, File> embeddedFiles) throws MavenFilteringException {
        Map<File, File> duplicateFiles = new HashMap<>();
        // See GRANITE-16348
        // we want to build a list of all the root directories in the order they were specified in the filter
        // but ignore the roots that don't point to a directory
        List<PathFilterSet> filterSets = filters.getFilterSets();
        if (filterSets.isEmpty()) {
            DefaultFileSet fileSet = createFileSet(jcrSourceDirectory, Constants.ROOT_DIR + prefix);
            duplicateFiles.putAll(getOverwrittenProtectedFiles(fileSet, false));
            addFileSetToArchive(mavenResourcesExecution, contentPackageArchiver, fileSet);
        } else {
            for (PathFilterSet filterSet : filterSets) {
                String relPath = PlatformNameFormat.getPlatformPath(filterSet.getRoot());
                String destPath = FileUtils.normalize(Constants.ROOT_DIR + prefix + relPath);

                // CQ-4204625 skip embedded files, they have been added already
                if (embeddedFiles.containsKey(destPath)) {
                    continue;
                }
                // check for full coverage aggregate
                File sourceFile = new File(jcrSourceDirectory, relPath + ".xml");
                if (sourceFile.isFile()) {
                    destPath = FileUtils.normalize(Constants.ROOT_DIR + prefix + relPath + ".xml");
                    if (isOverwritingProtectedFile(new File(destPath), sourceFile, false)) {
                        duplicateFiles.put(new File(destPath), sourceFile);
                    }
                    addFileToArchive(mavenResourcesExecution, contentPackageArchiver, sourceFile, destPath);
                    // similar to AbstractExporter all ancestors should be contained as well (see AggregateImpl.prepare(...))
                    addAncestors(contentPackageArchiver, sourceFile, jcrSourceDirectory, destPath);
                } else { 
                    // root path for ancestors is in one of the parent directories?
                    sourceFile = new File(jcrSourceDirectory, relPath);

                    // traverse the ancestors until we find a existing directory (see CQ-4204625)
                    while ((!sourceFile.exists() || !sourceFile.isDirectory())
                            && !jcrSourceDirectory.equals(sourceFile)) {
                        sourceFile = sourceFile.getParentFile();
                        relPath = StringUtils.chomp(relPath, "/");
                    }

                    if (!jcrSourceDirectory.equals(sourceFile)) {
                        destPath = FileUtils.normalize(Constants.ROOT_DIR + prefix + relPath);
                        DefaultFileSet fileSet = createFileSet(sourceFile, destPath + "/");
                        duplicateFiles.putAll(getOverwrittenProtectedFiles(fileSet, false));
                        addFileSetToArchive(mavenResourcesExecution, contentPackageArchiver, fileSet);
                        // similar to AbstractExporter all ancestors should be contained as well (see AggregateImpl.prepare(...))
                        addAncestors(contentPackageArchiver, sourceFile, jcrSourceDirectory, destPath);
                    }
                }
                
            }
        }
        return duplicateFiles;
    }

    private void addAncestors(ContentPackageArchiver contentPackageArchiver, File inputFile, File inputRootFile, String destFile) {
        // include up to (including root)
        if (!inputFile.getAbsolutePath().startsWith(inputRootFile.getAbsolutePath())) {
            return;
        }
        // is there an according .content.xml available? (ignore full-coverage files)
        File genericAggregate = new File(inputFile, Constants.DOT_CONTENT_XML);
        if (genericAggregate.exists()) {
            getLog().debug("Adding ancestor file '" + genericAggregate + "' to package at '" + destFile + "/" + Constants.DOT_CONTENT_XML +"'");
            contentPackageArchiver.addFile(genericAggregate, destFile + "/" + Constants.DOT_CONTENT_XML);
        }
        addAncestors(contentPackageArchiver, inputFile.getParentFile(), inputRootFile, StringUtils.chomp(destFile, "/"));
    }

    /** Checks if some files (optionally prefixed) below the given source directory are not listed in coveredFiles
     * 
     * @param sourceDirectory the source directory
     * @param prefix the optional prefix to prepend to the relative file name before comparing with {@code coveredFileNames}
     * @param coveredFileNames the covered file names (should have relative file names), might have OS specific separators
     * @return the absolute file names in the source directory which are not already listed in {@code entryNames}. */
    protected static Collection<File> getUncoveredFiles(final File sourceDirectory, Collection<String> excludes, String prefix,
            Collection<String> coveredFileNames) {
        // check for uncovered files (i.e. files from the source which are not even added to the content package)
        // entry name still have platform-dependent separators here (https://github.com/codehaus-plexus/plexus-archiver/issues/129)
        Collection<File> coveredFiles = coveredFileNames.stream()
                .map(File::new)
                .collect(Collectors.toList());

        /*
         * similar method as in {@link org.codehaus.plexus.components.io.resources.PlexusIoFileResourceCollection#getResources();}
         */
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(sourceDirectory);
        scanner.setExcludes(excludes.toArray(new String[0]));
        scanner.addDefaultExcludes();
        scanner.scan();

        Collection<File> allFiles = Stream.of(scanner.getIncludedFiles())
                .map(File::new)
                .collect(Collectors.toList());

        return getUncoveredFiles(sourceDirectory, prefix, allFiles, coveredFiles);
    }

    private static Collection<File> getUncoveredFiles(final File sourceDirectory, String prefix, final Collection<File> allFiles,
            final Collection<File> coveredFiles) {
        Collection<File> uncoveredFiles = new ArrayList<>();
        for (File file : allFiles) {
            if (!coveredFiles.contains(new File(Constants.ROOT_DIR + prefix, file.getPath()))) {
                uncoveredFiles.add(new File(sourceDirectory, file.getPath()));
            }
        }
        return uncoveredFiles;
    }

    private MavenArchiveConfiguration getMavenArchiveConfiguration(File manifestFile) {
        if (archive == null) {
            archive = new MavenArchiveConfiguration();

            archive.setAddMavenDescriptor(true);
            archive.setCompress(true);
            archive.setIndex(false);
        }
        // use the manifest being generated beforehand
        archive.setManifestFile(manifestFile);

        return archive;
    }
}

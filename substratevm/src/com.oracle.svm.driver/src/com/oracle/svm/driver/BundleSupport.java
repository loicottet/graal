/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.driver;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.file.CopyOption;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.util.json.JSONParserException;

import com.oracle.svm.core.OS;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.configure.ConfigurationParser;
import com.oracle.svm.core.option.BundleMember;
import com.oracle.svm.core.util.json.JsonPrinter;
import com.oracle.svm.core.util.json.JsonWriter;
import com.oracle.svm.util.ClassUtil;

final class BundleSupport {

    final NativeImage nativeImage;

    final Path rootDir;

    final Path stageDir;
    final Path classPathDir;
    final Path modulePathDir;
    final Path auxiliaryDir;
    final Path outputDir;
    final Path imagePathOutputDir;
    final Path auxiliaryOutputDir;

    Map<Path, Path> pathCanonicalizations = new HashMap<>();
    Map<Path, Path> pathSubstitutions = new HashMap<>();

    private final List<String> buildArgs;
    private Collection<String> updatedBuildArgs;

    boolean loadBundle;
    boolean writeBundle;

    private static final int BUNDLE_FILE_FORMAT_VERSION_MAJOR = 0;
    private static final int BUNDLE_FILE_FORMAT_VERSION_MINOR = 9;

    private static final String BUNDLE_INFO_MESSAGE_PREFIX = "GraalVM Native Image Bundle Support: ";
    private static final String BUNDLE_TEMP_DIR_PREFIX = "bundleRoot-";
    private static final String ORIGINAL_DIR_EXTENSION = ".orig";

    private Path bundlePath;
    private String bundleName;

    private final BundleProperties bundleProperties;

    static boolean allowBundleSupport;
    static final String UNLOCK_BUNDLE_SUPPORT_OPTION = "--enable-experimental-bundle-support";

    static final String BUNDLE_OPTION = "--bundle";
    static final String BUNDLE_FILE_EXTENSION = ".nib";

    private enum BundleOptionVariants {
        create(),
        apply()
    }

    static BundleSupport create(NativeImage nativeImage, String bundleArg, NativeImage.ArgumentQueue args) {
        if (!allowBundleSupport) {
            throw NativeImage.showError("Bundle support is still experimental and needs to be unlocked with " + UNLOCK_BUNDLE_SUPPORT_OPTION);
        }

        if (!nativeImage.userConfigProperties.isEmpty()) {
            throw NativeImage.showError("Bundle support cannot be combined with " + NativeImage.CONFIG_FILE_ENV_VAR_KEY + " environment variable use.");
        }

        try {
            String variant = bundleArg.substring(BUNDLE_OPTION.length() + 1);
            String bundleFilename = null;
            String[] variantParts = SubstrateUtil.split(variant, "=", 2);
            if (variantParts.length == 2) {
                variant = variantParts[0];
                bundleFilename = variantParts[1];
            }
            String applyOptionStr = BUNDLE_OPTION + "-" + BundleOptionVariants.apply;
            String createOptionStr = BUNDLE_OPTION + "-" + BundleOptionVariants.create;
            BundleSupport bundleSupport;
            switch (BundleOptionVariants.valueOf(variant)) {
                case apply:
                    if (nativeImage.useBundle()) {
                        if (nativeImage.bundleSupport.loadBundle) {
                            throw NativeImage.showError(String.format("native-image allows option %s to be specified only once.", applyOptionStr));
                        }
                        if (nativeImage.bundleSupport.writeBundle) {
                            throw NativeImage.showError(String.format("native-image option %s is not allowed to be used after option %s.", applyOptionStr, createOptionStr));
                        }
                    }
                    if (bundleFilename == null) {
                        throw NativeImage.showError(String.format("native-image option %s requires a bundle file argument. E.g. %s=bundle-file.nib.", applyOptionStr, applyOptionStr));
                    }
                    bundleSupport = new BundleSupport(nativeImage, bundleFilename);
                    /* Inject the command line args from the loaded bundle in-place */
                    List<String> buildArgs = bundleSupport.getBuildArgs();
                    for (int i = buildArgs.size() - 1; i >= 0; i--) {
                        args.push(buildArgs.get(i));
                    }
                    nativeImage.showVerboseMessage(nativeImage.isVerbose(), BUNDLE_INFO_MESSAGE_PREFIX + "Inject args: '" + String.join(" ", buildArgs) + "'");
                    /* Snapshot args after in-place expansion (includes also args after this one) */
                    bundleSupport.updatedBuildArgs = args.snapshot();
                    break;
                case create:
                    if (nativeImage.useBundle()) {
                        if (nativeImage.bundleSupport.writeBundle) {
                            throw NativeImage.showError(String.format("native-image allows option %s to be specified only once.", bundleArg));
                        } else {
                            bundleSupport = nativeImage.bundleSupport;
                            bundleSupport.writeBundle = true;
                        }
                    } else {
                        bundleSupport = new BundleSupport(nativeImage);
                    }
                    if (bundleFilename != null) {
                        bundleSupport.updateBundleLocation(Path.of(bundleFilename), true);
                    }
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            return bundleSupport;

        } catch (StringIndexOutOfBoundsException | IllegalArgumentException e) {
            String suggestedVariants = Arrays.stream(BundleOptionVariants.values())
                            .map(v -> BUNDLE_OPTION + "-" + v)
                            .collect(Collectors.joining(", "));
            throw NativeImage.showError("Unknown option " + bundleArg + ". Valid variants are: " + suggestedVariants + ".");
        }
    }

    private BundleSupport(NativeImage nativeImage) {
        Objects.requireNonNull(nativeImage);
        this.nativeImage = nativeImage;

        loadBundle = false;
        writeBundle = true;
        try {
            rootDir = Files.createTempDirectory(BUNDLE_TEMP_DIR_PREFIX);
            bundleProperties = new BundleProperties();

            Path inputDir = rootDir.resolve("input");
            stageDir = Files.createDirectories(inputDir.resolve("stage"));
            auxiliaryDir = Files.createDirectories(inputDir.resolve("auxiliary"));
            Path classesDir = inputDir.resolve("classes");
            classPathDir = Files.createDirectories(classesDir.resolve("cp"));
            modulePathDir = Files.createDirectories(classesDir.resolve("p"));
            outputDir = rootDir.resolve("output");
            imagePathOutputDir = Files.createDirectories(outputDir.resolve("default"));
            auxiliaryOutputDir = Files.createDirectories(outputDir.resolve("other"));
        } catch (IOException e) {
            throw NativeImage.showError("Unable to create bundle directory layout", e);
        }
        this.buildArgs = Collections.unmodifiableList(nativeImage.config.getBuildArgs());
    }

    private BundleSupport(NativeImage nativeImage, String bundleFilenameArg) {
        Objects.requireNonNull(nativeImage);
        this.nativeImage = nativeImage;

        loadBundle = true;
        writeBundle = false;

        Objects.requireNonNull(bundleFilenameArg);
        updateBundleLocation(Path.of(bundleFilenameArg), false);

        try {
            rootDir = Files.createTempDirectory(BUNDLE_TEMP_DIR_PREFIX);
            bundleProperties = new BundleProperties();

            outputDir = rootDir.resolve("output");
            String originalOutputDirName = outputDir.getFileName().toString() + ORIGINAL_DIR_EXTENSION;

            Path bundleFilePath = bundlePath.resolve(bundleName + BUNDLE_FILE_EXTENSION);
            try (JarFile archive = new JarFile(bundleFilePath.toFile())) {
                archive.stream().forEach(jarEntry -> {
                    Path bundleEntry = rootDir.resolve(jarEntry.getName());
                    if (bundleEntry.startsWith(outputDir)) {
                        /* Extract original output to different path */
                        bundleEntry = rootDir.resolve(originalOutputDirName).resolve(outputDir.relativize(bundleEntry));
                    }
                    try {
                        Path bundleFileParent = bundleEntry.getParent();
                        if (bundleFileParent != null) {
                            Files.createDirectories(bundleFileParent);
                        }
                        Files.copy(archive.getInputStream(jarEntry), bundleEntry);
                    } catch (IOException e) {
                        throw NativeImage.showError("Unable to copy " + jarEntry.getName() + " from bundle " + bundleEntry + " to " + bundleEntry, e);
                    }
                });
            }
        } catch (IOException e) {
            throw NativeImage.showError("Unable to expand bundle directory layout from bundle file " + bundleName + BUNDLE_FILE_EXTENSION, e);
        }

        bundleProperties.loadAndVerify();

        try {
            Path inputDir = rootDir.resolve("input");
            stageDir = Files.createDirectories(inputDir.resolve("stage"));
            auxiliaryDir = Files.createDirectories(inputDir.resolve("auxiliary"));
            Path classesDir = inputDir.resolve("classes");
            classPathDir = Files.createDirectories(classesDir.resolve("cp"));
            modulePathDir = Files.createDirectories(classesDir.resolve("p"));
            imagePathOutputDir = Files.createDirectories(outputDir.resolve("default"));
            auxiliaryOutputDir = Files.createDirectories(outputDir.resolve("other"));
        } catch (IOException e) {
            throw NativeImage.showError("Unable to create bundle directory layout", e);
        }

        Path pathCanonicalizationsFile = stageDir.resolve("path_canonicalizations.json");
        try (Reader reader = Files.newBufferedReader(pathCanonicalizationsFile)) {
            new PathMapParser(pathCanonicalizations).parseAndRegister(reader);
        } catch (IOException e) {
            throw NativeImage.showError("Failed to read bundle-file " + pathCanonicalizationsFile, e);
        }
        Path pathSubstitutionsFile = stageDir.resolve("path_substitutions.json");
        try (Reader reader = Files.newBufferedReader(pathSubstitutionsFile)) {
            new PathMapParser(pathSubstitutions).parseAndRegister(reader);
        } catch (IOException e) {
            throw NativeImage.showError("Failed to read bundle-file " + pathSubstitutionsFile, e);
        }
        Path buildArgsFile = stageDir.resolve("build.json");
        try (Reader reader = Files.newBufferedReader(buildArgsFile)) {
            List<String> buildArgsFromFile = new ArrayList<>();
            new BuildArgsParser(buildArgsFromFile).parseAndRegister(reader);
            buildArgs = Collections.unmodifiableList(buildArgsFromFile);
        } catch (IOException e) {
            throw NativeImage.showError("Failed to read bundle-file " + pathSubstitutionsFile, e);
        }
    }

    public List<String> getBuildArgs() {
        return buildArgs;
    }

    Path recordCanonicalization(Path before, Path after) {
        if (before.startsWith(rootDir)) {
            nativeImage.showVerboseMessage(nativeImage.isVVerbose(), "RecordCanonicalization Skip: " + before);
            return before;
        }
        if (after.startsWith(nativeImage.config.getJavaHome())) {
            return after;
        }
        nativeImage.showVerboseMessage(nativeImage.isVVerbose(), "RecordCanonicalization src: " + before + ", dst: " + after);
        pathCanonicalizations.put(before, after);
        return after;
    }

    Path restoreCanonicalization(Path before) {
        Path after = pathCanonicalizations.get(before);
        nativeImage.showVerboseMessage(after != null && nativeImage.isVVerbose(), "RestoreCanonicalization src: " + before + ", dst: " + after);
        return after;
    }

    Path substituteAuxiliaryPath(Path origPath, BundleMember.Role bundleMemberRole) {
        Path destinationDir = switch (bundleMemberRole) {
            case Input -> auxiliaryDir;
            case Output -> auxiliaryOutputDir;
            case Ignore -> null;
        };
        if (destinationDir == null) {
            return origPath;
        }
        return substitutePath(origPath, destinationDir);
    }

    Path substituteImagePath(Path origPath) {
        pathSubstitutions.put(origPath, rootDir.relativize(imagePathOutputDir));
        return imagePathOutputDir;
    }

    Path substituteClassPath(Path origPath) {
        try {
            return substitutePath(origPath, classPathDir);
        } catch (BundlePathSubstitutionError error) {
            throw NativeImage.showError("Failed to prepare class-path entry '" + error.origPath + "' for bundle inclusion.", error);
        }
    }

    Path substituteModulePath(Path origPath) {
        try {
            return substitutePath(origPath, modulePathDir);
        } catch (BundlePathSubstitutionError error) {
            throw NativeImage.showError("Failed to prepare module-path entry '" + error.origPath + "' for bundle inclusion.", error);
        }
    }

    @SuppressWarnings("serial")
    static final class BundlePathSubstitutionError extends Error {
        public final Path origPath;

        BundlePathSubstitutionError(String message, Path origPath) {
            super(message);
            this.origPath = origPath;
        }
    }

    @SuppressWarnings("try")
    private Path substitutePath(Path origPath, Path destinationDir) {
        assert destinationDir.startsWith(rootDir);

        if (origPath.startsWith(rootDir)) {
            nativeImage.showVerboseMessage(nativeImage.isVVerbose(), "RecordSubstitution/RestoreSubstitution Skip: " + origPath);
            return origPath;
        }

        Path previousRelativeSubstitutedPath = pathSubstitutions.get(origPath);
        if (previousRelativeSubstitutedPath != null) {
            nativeImage.showVerboseMessage(nativeImage.isVVerbose(), "RestoreSubstitution src: " + origPath + ", dst: " + previousRelativeSubstitutedPath);
            return rootDir.resolve(previousRelativeSubstitutedPath);
        }

        if (origPath.startsWith(nativeImage.config.getJavaHome())) {
            /* If origPath comes from native-image itself, substituting is not needed. */
            return origPath;
        }

        boolean forbiddenPath = false;
        if (!OS.WINDOWS.isCurrent()) {
            Path tmpPath = ClassUtil.CLASS_MODULE_PATH_EXCLUDE_DIRECTORIES_ROOT.resolve("tmp");
            boolean subdirInTmp = origPath.startsWith(tmpPath) && !origPath.equals(tmpPath);
            if (!subdirInTmp) {
                Set<Path> forbiddenPaths = new HashSet<>(ClassUtil.CLASS_MODULE_PATH_EXCLUDE_DIRECTORIES);
                forbiddenPaths.add(rootDir);
                for (Path path : forbiddenPaths) {
                    if (origPath.startsWith(path)) {
                        forbiddenPath = true;
                        break;
                    }
                }
            }
        }
        for (Path rootDirectory : FileSystems.getDefault().getRootDirectories()) {
            /* Refuse /, C:, D:, ... */
            if (origPath.equals(rootDirectory)) {
                forbiddenPath = true;
                break;
            }
        }
        if (forbiddenPath) {
            throw new BundlePathSubstitutionError("Bundles do not allow inclusion of directory " + origPath, origPath);
        }

        boolean isOutputPath = destinationDir.startsWith(outputDir);

        if (!isOutputPath && !Files.isReadable(origPath)) {
            /* Prevent subsequent retries to substitute invalid paths */
            pathSubstitutions.put(origPath, origPath);
            return origPath;
        }

        // TODO Report error if overlapping dir-trees are passed in
        // TODO add .endsWith(ClasspathUtils.cpWildcardSubstitute) handling (copy whole directory)
        String origFileName = origPath.getFileName().toString();
        int extensionPos = origFileName.lastIndexOf('.');
        String baseName;
        String extension;
        if (extensionPos > 0) {
            baseName = origFileName.substring(0, extensionPos);
            extension = origFileName.substring(extensionPos);
        } else {
            baseName = origFileName;
            extension = "";
        }

        Path substitutedPath = destinationDir.resolve(baseName + extension);
        int collisionIndex = 0;
        while (Files.exists(substitutedPath)) {
            collisionIndex += 1;
            substitutedPath = destinationDir.resolve(baseName + "_" + collisionIndex + extension);
        }

        if (!isOutputPath) {
            copyFiles(origPath, substitutedPath, false);
        }

        Path relativeSubstitutedPath = rootDir.relativize(substitutedPath);
        nativeImage.showVerboseMessage(nativeImage.isVVerbose(), "RecordSubstitution src: " + origPath + ", dst: " + relativeSubstitutedPath);
        pathSubstitutions.put(origPath, relativeSubstitutedPath);
        return substitutedPath;
    }

    private void copyFiles(Path source, Path target, boolean overwrite) {
        nativeImage.showVerboseMessage(nativeImage.isVVerbose(), "> Copy files from " + source + " to " + target);
        if (Files.isDirectory(source)) {
            try (Stream<Path> walk = Files.walk(source)) {
                walk.forEach(sourcePath -> copyFile(sourcePath, target.resolve(source.relativize(sourcePath)), overwrite));
            } catch (IOException e) {
                throw NativeImage.showError("Failed to iterate through directory " + source, e);
            }
        } else {
            copyFile(source, target, overwrite);
        }
    }

    private void copyFile(Path sourceFile, Path target, boolean overwrite) {
        try {
            nativeImage.showVerboseMessage(nativeImage.isVVVerbose(), "> Copy " + sourceFile + " to " + target);
            if (overwrite && Files.isDirectory(sourceFile) && Files.isDirectory(target)) {
                return;
            }
            CopyOption[] options = overwrite ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new CopyOption[0];
            Files.copy(sourceFile, target, options);
        } catch (IOException e) {
            throw NativeImage.showError("Failed to copy " + sourceFile + " to " + target, e);
        }
    }

    void complete() {
        boolean writeOutput;
        try (Stream<Path> pathOutputFiles = Files.list(imagePathOutputDir); Stream<Path> auxiliaryOutputFiles = Files.list(auxiliaryOutputDir)) {
            writeOutput = pathOutputFiles.findAny().isPresent() || auxiliaryOutputFiles.findAny().isPresent();
        } catch (IOException e) {
            throw NativeImage.showError("Unable to determine if bundle output should be written.");
        }

        /*
         * In the unlikely case of writing a bundle but no location got specified so far, provide a
         * final fallback here. Can happen when something goes wrong in bundle processing itself.
         */
        if (bundlePath == null) {
            bundlePath = nativeImage.config.getWorkingDirectory();
            bundleName = "unknown";
        }

        if (!nativeImage.isDryRun() && (writeOutput || writeBundle)) {
            nativeImage.showNewline();
        }

        if (writeOutput) {
            Path externalOutputDir = bundlePath.resolve(bundleName + "." + outputDir.getFileName());
            copyFiles(outputDir, externalOutputDir, true);
            nativeImage.showMessage(BUNDLE_INFO_MESSAGE_PREFIX + "Bundle build output written to " + externalOutputDir);
        }

        try {
            if (writeBundle) {
                Path bundleFilePath = writeBundle();
                nativeImage.showMessage(BUNDLE_INFO_MESSAGE_PREFIX + "Bundle written to " + bundleFilePath);
            }
        } finally {
            nativeImage.showNewline();
            nativeImage.deleteAllFiles(rootDir);
        }
    }

    void updateBundleLocation(Path bundleFile, boolean redefine) {
        if (redefine) {
            bundlePath = null;
            bundleName = null;
        }

        if (bundlePath != null) {
            Objects.requireNonNull(bundleName);
            /* Bundle location is already set */
            return;
        }
        Path bundleFilePath = bundleFile.toAbsolutePath();
        String bundleFileName = bundleFile.getFileName().toString();
        if (!bundleFileName.endsWith(BUNDLE_FILE_EXTENSION)) {
            throw NativeImage.showError("The given bundle file " + bundleFileName + " does not end with '" + BUNDLE_FILE_EXTENSION + "'");
        }
        if (Files.isDirectory(bundleFilePath)) {
            throw NativeImage.showError("The given bundle file " + bundleFileName + " is a directory and not a file");
        }
        if (loadBundle && !redefine) {
            if (!Files.isReadable(bundleFilePath)) {
                throw NativeImage.showError("The given bundle file " + bundleFileName + " cannot be read.");
            }
        }
        Path newBundlePath = bundleFilePath.getParent();
        if (writeBundle) {
            if (!Files.isWritable(newBundlePath)) {
                throw NativeImage.showError("The bundle file directory " + newBundlePath + " is not writeable.");
            }
            if (Files.exists(bundleFilePath) && !Files.isWritable(bundleFilePath)) {
                throw NativeImage.showError("The given bundle file " + bundleFileName + " is not writeable.");
            }
        }
        bundlePath = newBundlePath;
        bundleName = bundleFileName.substring(0, bundleFileName.length() - BUNDLE_FILE_EXTENSION.length());
    }

    private Path writeBundle() {
        String originalOutputDirName = outputDir.getFileName().toString() + ORIGINAL_DIR_EXTENSION;
        Path originalOutputDir = rootDir.resolve(originalOutputDirName);
        if (Files.exists(originalOutputDir)) {
            nativeImage.deleteAllFiles(originalOutputDir);
        }

        Path metaInfDir = rootDir.resolve(JarFile.MANIFEST_NAME);
        if (Files.exists(metaInfDir)) {
            nativeImage.deleteAllFiles(metaInfDir);
        }

        Path pathCanonicalizationsFile = stageDir.resolve("path_canonicalizations.json");
        try (JsonWriter writer = new JsonWriter(pathCanonicalizationsFile)) {
            /* Printing as list with defined sort-order ensures useful diffs are possible */
            JsonPrinter.printCollection(writer, pathCanonicalizations.entrySet(), Map.Entry.comparingByKey(), BundleSupport::printPathMapping);
        } catch (IOException e) {
            throw NativeImage.showError("Failed to write bundle-file " + pathCanonicalizationsFile, e);
        }
        Path pathSubstitutionsFile = stageDir.resolve("path_substitutions.json");
        try (JsonWriter writer = new JsonWriter(pathSubstitutionsFile)) {
            /* Printing as list with defined sort-order ensures useful diffs are possible */
            JsonPrinter.printCollection(writer, pathSubstitutions.entrySet(), Map.Entry.comparingByKey(), BundleSupport::printPathMapping);
        } catch (IOException e) {
            throw NativeImage.showError("Failed to write bundle-file " + pathSubstitutionsFile, e);
        }

        Path buildArgsFile = stageDir.resolve("build.json");
        try (JsonWriter writer = new JsonWriter(buildArgsFile)) {
            ArrayList<String> cleanBuildArgs = new ArrayList<>();
            for (String buildArg : updatedBuildArgs != null ? updatedBuildArgs : buildArgs) {
                if (buildArg.equals(UNLOCK_BUNDLE_SUPPORT_OPTION)) {
                    continue;
                }
                if (buildArg.startsWith(BUNDLE_OPTION)) {
                    continue;
                }
                if (buildArg.startsWith(nativeImage.oHPath)) {
                    continue;
                }
                if (buildArg.equals(CmdLineOptionHandler.VERBOSE_OPTION)) {
                    continue;
                }
                if (buildArg.equals(CmdLineOptionHandler.DRY_RUN_OPTION)) {
                    continue;
                }
                if (buildArg.startsWith("-Dllvm.bin.dir=")) {
                    Optional<String> existing = nativeImage.config.getBuildArgs().stream().filter(arg -> arg.startsWith("-Dllvm.bin.dir=")).findFirst();
                    if (existing.isPresent() && !existing.get().equals(buildArg)) {
                        throw NativeImage.showError("Bundle native-image argument '" + buildArg + "' conflicts with existing '" + existing.get() + "'.");
                    }
                    continue;
                }
                cleanBuildArgs.add(buildArg);
            }
            /* Printing as list with defined sort-order ensures useful diffs are possible */
            JsonPrinter.printCollection(writer, cleanBuildArgs, null, BundleSupport::printBuildArg);
        } catch (IOException e) {
            throw NativeImage.showError("Failed to write bundle-file " + pathSubstitutionsFile, e);
        }

        bundleProperties.write();

        Path bundleFilePath = bundlePath.resolve(bundleName + BUNDLE_FILE_EXTENSION);
        try (JarOutputStream jarOutStream = new JarOutputStream(Files.newOutputStream(bundleFilePath), createManifest())) {
            try (Stream<Path> walk = Files.walk(rootDir)) {
                walk.filter(Predicate.not(Files::isDirectory)).forEach(bundleEntry -> {
                    String jarEntryName = rootDir.relativize(bundleEntry).toString();
                    JarEntry entry = new JarEntry(jarEntryName.replace(File.separator, "/"));
                    try {
                        entry.setTime(Files.getLastModifiedTime(bundleEntry).toMillis());
                        jarOutStream.putNextEntry(entry);
                        Files.copy(bundleEntry, jarOutStream);
                        jarOutStream.closeEntry();
                    } catch (IOException e) {
                        throw NativeImage.showError("Failed to copy " + bundleEntry + " into bundle file " + bundleFilePath.getFileName(), e);
                    }
                });
            }
        } catch (IOException e) {
            throw NativeImage.showError("Failed to create bundle file " + bundleFilePath.getFileName(), e);
        }

        return bundleFilePath;
    }

    private static Manifest createManifest() {
        Manifest mf = new Manifest();
        Attributes attributes = mf.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        /* If we add run-bundle-as-java-application a launcher mainclass would be added here */
        return mf;
    }

    private static final String substitutionMapSrcField = "src";
    private static final String substitutionMapDstField = "dst";

    private static void printPathMapping(Map.Entry<Path, Path> entry, JsonWriter w) throws IOException {
        w.append('{').quote(substitutionMapSrcField).append(" : ").quote(entry.getKey());
        w.append(',').quote(substitutionMapDstField).append(':').quote(entry.getValue());
        w.append('}');
    }

    private static void printBuildArg(String entry, JsonWriter w) throws IOException {
        w.quote(entry);
    }

    private static final class PathMapParser extends ConfigurationParser {

        private final Map<Path, Path> pathMap;

        private PathMapParser(Map<Path, Path> pathMap) {
            super(true);
            this.pathMap = pathMap;
        }

        @Override
        public void parseAndRegister(Object json, URI origin) {
            for (var rawEntry : asList(json, "Expected a list of path substitution objects")) {
                var entry = asMap(rawEntry, "Expected a substitution object");
                Object srcPathString = entry.get(substitutionMapSrcField);
                if (srcPathString == null) {
                    throw new JSONParserException("Expected " + substitutionMapSrcField + "-field in substitution object");
                }
                Object dstPathString = entry.get(substitutionMapDstField);
                if (dstPathString == null) {
                    throw new JSONParserException("Expected " + substitutionMapDstField + "-field in substitution object");
                }
                pathMap.put(Path.of(srcPathString.toString()), Path.of(dstPathString.toString()));
            }
        }
    }

    private static final class BuildArgsParser extends ConfigurationParser {

        private final List<String> args;

        private BuildArgsParser(List<String> args) {
            super(true);
            this.args = args;
        }

        @Override
        public void parseAndRegister(Object json, URI origin) {
            for (var arg : asList(json, "Expected a list of arguments")) {
                args.add(arg.toString());
            }
        }
    }

    private static final Path bundlePropertiesFileName = Path.of("META-INF/nibundle.properties");

    private final class BundleProperties {

        private static final String PROPERTY_KEY_BUNDLE_FILE_VERSION_MAJOR = "BundleFileVersionMajor";
        private static final String PROPERTY_KEY_BUNDLE_FILE_VERSION_MINOR = "BundleFileVersionMinor";
        private static final String PROPERTY_KEY_BUNDLE_FILE_CREATION_TIMESTAMP = "BundleFileCreationTimestamp";
        private static final String PROPERTY_KEY_IMAGE_BUILT = "ImageBuilt";
        private static final String PROPERTY_KEY_BUILT_WITH_CONTAINER = "BuiltWithContainer";
        private static final String PROPERTY_KEY_NATIVE_IMAGE_PLATFORM = "NativeImagePlatform";
        private static final String PROPERTY_KEY_NATIVE_IMAGE_VERSION = "NativeImageVersion";

        private final Path bundlePropertiesFile;
        private final Map<String, String> properties;

        private BundleProperties() {
            Objects.requireNonNull(rootDir);
            Objects.requireNonNull(nativeImage);

            bundlePropertiesFile = rootDir.resolve(bundlePropertiesFileName);
            properties = new HashMap<>();
        }

        private void loadAndVerify() {
            Objects.requireNonNull(bundleName);

            String bundleFileName = bundlePath.resolve(bundleName + BUNDLE_FILE_EXTENSION).toString();
            if (!Files.isReadable(bundlePropertiesFile)) {
                throw NativeImage.showError("The given bundle file " + bundleFileName + " does not contain a bundle properties file");
            }

            properties.putAll(NativeImage.loadProperties(bundlePropertiesFile));
            String fileVersionKey = PROPERTY_KEY_BUNDLE_FILE_VERSION_MAJOR;
            try {
                int major = Integer.parseInt(properties.getOrDefault(fileVersionKey, "-1"));
                fileVersionKey = PROPERTY_KEY_BUNDLE_FILE_VERSION_MINOR;
                int minor = Integer.parseInt(properties.getOrDefault(fileVersionKey, "-1"));
                String message = String.format("The given bundle file %s was created with newer bundle-file-format version %d.%d" +
                                " (current %d.%d). Update to the latest version of native-image.", bundleFileName, major, minor, BUNDLE_FILE_FORMAT_VERSION_MAJOR, BUNDLE_FILE_FORMAT_VERSION_MINOR);
                if (major > BUNDLE_FILE_FORMAT_VERSION_MAJOR) {
                    throw NativeImage.showError(message);
                } else if (major == BUNDLE_FILE_FORMAT_VERSION_MAJOR) {
                    if (minor > BUNDLE_FILE_FORMAT_VERSION_MINOR) {
                        NativeImage.showWarning(message);
                    }
                }
            } catch (NumberFormatException e) {
                throw NativeImage.showError(fileVersionKey + " in " + bundlePropertiesFileName + " is missing or ill-defined", e);
            }
            String bundleVersion = properties.getOrDefault(PROPERTY_KEY_NATIVE_IMAGE_VERSION, "unknown");
            String currentVersion = bundleVersion.equals(NativeImage.getNativeImageVersion()) ? "" : " != '" + NativeImage.getNativeImageVersion() + "'";
            String bundlePlatform = properties.getOrDefault(PROPERTY_KEY_NATIVE_IMAGE_PLATFORM, "unknown");
            String currentPlatform = bundlePlatform.equals(NativeImage.platform) ? "" : " != '" + NativeImage.platform + "'";
            String bundleCreationTimestamp = properties.getOrDefault(PROPERTY_KEY_BUNDLE_FILE_CREATION_TIMESTAMP, "");
            String localDateStr;
            try {
                ZonedDateTime dateTime = ZonedDateTime.parse(bundleCreationTimestamp, DateTimeFormatter.ISO_DATE_TIME);
                localDateStr = dateTime.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL));
            } catch (DateTimeParseException e) {
                localDateStr = "unknown time";
            }
            nativeImage.showNewline();
            nativeImage.showMessage(String.format("%sLoaded Bundle from %s", BUNDLE_INFO_MESSAGE_PREFIX, bundleFileName));
            nativeImage.showMessage(String.format("%sBundle created at '%s'", BUNDLE_INFO_MESSAGE_PREFIX, localDateStr));
            nativeImage.showMessage(String.format("%sUsing version: '%s'%s on platform: '%s'%s", BUNDLE_INFO_MESSAGE_PREFIX, bundleVersion, currentVersion, bundlePlatform, currentPlatform));
        }

        private void write() {
            properties.put(PROPERTY_KEY_BUNDLE_FILE_VERSION_MAJOR, String.valueOf(BUNDLE_FILE_FORMAT_VERSION_MAJOR));
            properties.put(PROPERTY_KEY_BUNDLE_FILE_VERSION_MINOR, String.valueOf(BUNDLE_FILE_FORMAT_VERSION_MINOR));
            properties.put(PROPERTY_KEY_BUNDLE_FILE_CREATION_TIMESTAMP, ZonedDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
            boolean imageBuilt = !nativeImage.isDryRun();
            properties.put(PROPERTY_KEY_IMAGE_BUILT, String.valueOf(imageBuilt));
            if (imageBuilt) {
                properties.put(PROPERTY_KEY_BUILT_WITH_CONTAINER, String.valueOf(false));
            }
            properties.put(PROPERTY_KEY_NATIVE_IMAGE_PLATFORM, NativeImage.platform);
            properties.put(PROPERTY_KEY_NATIVE_IMAGE_VERSION, NativeImage.getNativeImageVersion());
            NativeImage.ensureDirectoryExists(bundlePropertiesFile.getParent());
            try (OutputStream outputStream = Files.newOutputStream(bundlePropertiesFile)) {
                Properties p = new Properties();
                p.putAll(properties);
                p.store(outputStream, "Native Image bundle file properties");
            } catch (IOException e) {
                throw NativeImage.showError("Creating bundle properties file " + bundlePropertiesFileName + " failed", e);
            }
        }
    }
}
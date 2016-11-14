/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.file.collections;

import org.gradle.api.file.*;
import org.gradle.api.internal.file.CachingFileVisitDetails;
import org.gradle.api.internal.file.FileSystemSubset;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.util.PatternFilterable;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.Factory;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.nativeintegration.services.FileSystems;
import org.gradle.util.GFileUtils;
import org.gradle.util.GUtil;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Directory walker supporting {@link Spec}s for includes and excludes.
 * The file system is traversed in depth-first prefix order - all files in a directory will be
 * visited before any child directory is visited.
 *
 * A file or directory will only be visited if it matches all includes and no
 * excludes.
 */
public class DirectoryFileTree implements MinimalFileTree, PatternFilterableFileTree, RandomAccessFileCollection, LocalFileTree, DirectoryTree {
    private static final Logger LOGGER = Logging.getLogger(DirectoryFileTree.class);

    private final File dir;

    private PatternSet patternSet;
    private boolean postfix;
    private final FileSystem fileSystem = FileSystems.getDefault();
    private static final Factory<DirectoryWalker> DEFAULT_DIRECTORY_WALKER_FACTORY = new DefaultDirectoryWalkerFactory();
    private final Factory<DirectoryWalker> directoryWalkerFactory;

    public DirectoryFileTree(File dir) {
        this(dir, new PatternSet());
    }

    public DirectoryFileTree(File dir, PatternSet patternSet) {
        this(dir, patternSet, DEFAULT_DIRECTORY_WALKER_FACTORY);
    }

    DirectoryFileTree(File dir, PatternSet patternSet, Factory<DirectoryWalker> directoryWalkerFactory) {
        this.patternSet = patternSet;
        this.dir = GFileUtils.canonicalise(dir);
        this.directoryWalkerFactory = directoryWalkerFactory;
    }


    public String getDisplayName() {
        String includes = patternSet.getIncludes().isEmpty() ? "" : String.format(" include %s", GUtil.toString(patternSet.getIncludes()));
        String excludes = patternSet.getExcludes().isEmpty() ? "" : String.format(" exclude %s", GUtil.toString(patternSet.getExcludes()));
        return String.format("directory '%s'%s%s", dir, includes, excludes);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    public PatternSet getPatterns() {
        return patternSet;
    }

    public File getDir() {
        return dir;
    }

    public Collection<DirectoryFileTree> getLocalContents() {
        return Collections.singletonList(this);
    }

    public DirectoryFileTree filter(PatternFilterable patterns) {
        PatternSet patternSet = this.patternSet.intersect();
        patternSet.copyFrom(patterns);
        return new DirectoryFileTree(dir, patternSet, directoryWalkerFactory);
    }

    public boolean contains(File file) {
        return DirectoryTrees.contains(fileSystem, this, file) && file.isFile();
    }

    @Override
    public void registerWatchPoints(FileSystemSubset.Builder builder) {
        builder.add(this);
    }

    /**
     * Process the specified file or directory.  Note that the startFile parameter
     * may be either a directory or a file.  If it is a directory, then its contents
     * (but not the directory itself) will be checked with isAllowed and notified to
     * the listener.  If it is a file, the file will be checked and notified.
     */
    public void visit(FileVisitor visitor) {
        visitFrom(visitor, dir, new RelativePath(false));
    }

    public void visitFrom(FileVisitor visitor, File dir, RelativePath path) {
        AtomicBoolean stopFlag = new AtomicBoolean();
        Spec<FileTreeElement> spec = patternSet.getAsSpec();
        if (dir.exists()) {
            if (dir.isFile()) {
                processSingleFile(dir, visitor, spec, stopFlag);
            } else {
                walkDir(dir, path, visitor, spec, stopFlag);
            }
        } else {
            LOGGER.info("file or directory '{}', not found", dir);
        }
    }

    private void processSingleFile(File file, FileVisitor visitor, Spec<FileTreeElement> spec, AtomicBoolean stopFlag) {
        RelativePath path = new RelativePath(true, file.getName());
        FileVisitDetails details = new CachingFileVisitDetails(file, path, stopFlag, fileSystem, fileSystem, false);
        if (isAllowed(details, spec)) {
            visitor.visitFile(details);
        }
    }

    private void walkDir(File file, RelativePath path, FileVisitor visitor, Spec<FileTreeElement> spec, AtomicBoolean stopFlag) {
        directoryWalkerFactory.create().walkDir(file, path, visitor, spec, stopFlag, fileSystem, postfix);
    }

    static boolean isAllowed(FileTreeElement element, Spec<FileTreeElement> spec) {
        return spec.isSatisfiedBy(element);
    }

    /**
     * Traverse directories (but not files) in postfix rather than prefix order.
     *
     * @return {@code this}
     */
    public DirectoryFileTree postfix() {
        postfix = true;
        return this;
    }

    public PatternSet getPatternSet() {
        return patternSet;
    }

}
/*
 * Copyright (C) 2018 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.briefcase.export;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.newInputStream;
import static org.opendatakit.briefcase.reused.UncheckedFiles.exists;
import static org.opendatakit.briefcase.ui.reused.FileChooser.isUnderBriefcaseFolder;
import static org.opendatakit.briefcase.util.FileSystemUtils.isUnderODKFolder;
import static org.opendatakit.briefcase.util.StringUtils.stripIllegalChars;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.bouncycastle.openssl.PEMReader;
import org.opendatakit.briefcase.model.BriefcasePreferences;
import org.opendatakit.briefcase.reused.BriefcaseException;
import org.opendatakit.briefcase.reused.OverridableBoolean;
import org.opendatakit.briefcase.reused.TriStateBoolean;
import org.opendatakit.briefcase.reused.UncheckedFiles;
import org.opendatakit.briefcase.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExportConfiguration {
  private static final Logger log = LoggerFactory.getLogger(ExportConfiguration.class);
  private static final String EXPORT_DIR = "exportDir";
  private static final String PEM_FILE = "pemFile";
  private static final String START_DATE = "startDate";
  private static final String END_DATE = "endDate";
  private static final String PULL_BEFORE = "pullBefore";
  private static final String PULL_BEFORE_OVERRIDE = "pullBeforeOverride";
  private static final String OVERWRITE_FILES = "overwriteExistingFiles";
  private static final String OVERWRITE_FILES_OVERRIDE = "overwriteFilesOverride";
  private static final String EXPORT_MEDIA = "exportMedia";
  private static final String EXPORT_MEDIA_OVERRIDE = "exportMediaOverride";
  private static final String SPLIT_SELECT_MULTIPLES = "splitSelectMultiples";
  private static final String SPLIT_SELECT_MULTIPLES_OVERRIDE = "splitSelectMultiplesOverride";
  private final Optional<String> exportFileName;
  private final Optional<Path> exportDir;
  private final Optional<Path> pemFile;
  private final DateRange dateRange;
  private final OverridableBoolean pullBefore;
  private final OverridableBoolean overwriteFiles;
  private final OverridableBoolean exportMedia;
  private final OverridableBoolean splitSelectMultiples;

  public ExportConfiguration(Optional<String> exportFileName, Optional<Path> exportDir, Optional<Path> pemFile, DateRange dateRange, OverridableBoolean pullBefore, OverridableBoolean overwriteFiles, OverridableBoolean exportMedia, OverridableBoolean splitSelectMultiples) {
    checkInvariants(exportDir, pemFile);
    this.exportFileName = exportFileName;
    this.exportDir = exportDir;
    this.pemFile = pemFile;
    this.dateRange = dateRange;
    this.pullBefore = pullBefore;
    this.overwriteFiles = overwriteFiles;
    this.exportMedia = exportMedia;
    this.splitSelectMultiples = splitSelectMultiples;
  }

  private static void checkInvariants(Optional<Path> exportDir, Optional<Path> pemFile) {
    exportDir.ifPresent(path -> {
      if (!exists(path))
        throw new IllegalArgumentException("Given export directory doesn't exist");
      if (!isDirectory(path))
        throw new IllegalArgumentException("Given export directory is not a directory");
      if (isUnderODKFolder(path.toFile()))
        throw new IllegalArgumentException("Given export directory is inside a Collect storage directory");
      if (isUnderBriefcaseFolder(path.toFile()))
        throw new IllegalArgumentException("Given export directory is inside a Briefcase storage directory");
    });
    pemFile.ifPresent(path -> {
      if (!exists(path))
        throw new IllegalArgumentException("Given PEM file doesn't exist");
      if (!isRegularFile(path))
        throw new IllegalArgumentException("Given PEM file is not a file");
      if (!readPemFile(path).isPresent())
        throw new IllegalArgumentException("Given PEM file can't be parsed");
    });
  }

  public static ExportConfiguration empty() {
    return new ExportConfiguration(Optional.empty(), Optional.empty(), Optional.empty(), DateRange.empty(), OverridableBoolean.empty(), OverridableBoolean.empty(), OverridableBoolean.empty(), OverridableBoolean.empty());
  }

  public static ExportConfiguration load(BriefcasePreferences prefs) {
    return load(prefs, "");
  }

  public static ExportConfiguration load(BriefcasePreferences prefs, String keyPrefix) {
    Optional<LocalDate> startDate = prefs.nullSafeGet(keyPrefix + START_DATE).map(LocalDate::parse);
    Optional<LocalDate> endDate = prefs.nullSafeGet(keyPrefix + END_DATE).map(LocalDate::parse);
    return new ExportConfiguration(
        Optional.empty(),
        prefs.nullSafeGet(keyPrefix + EXPORT_DIR).map(Paths::get),
        prefs.nullSafeGet(keyPrefix + PEM_FILE).map(Paths::get),
        new DateRange(startDate, endDate),
        readOverridableBoolean(prefs, keyPrefix + PULL_BEFORE, keyPrefix + PULL_BEFORE_OVERRIDE),
        readOverridableBoolean(prefs, keyPrefix + OVERWRITE_FILES, keyPrefix + OVERWRITE_FILES_OVERRIDE),
        readOverridableBoolean(prefs, keyPrefix + EXPORT_MEDIA, keyPrefix + EXPORT_MEDIA_OVERRIDE),
        readOverridableBoolean(prefs, keyPrefix + SPLIT_SELECT_MULTIPLES, keyPrefix + SPLIT_SELECT_MULTIPLES_OVERRIDE)
    );
  }

  private static OverridableBoolean readOverridableBoolean(BriefcasePreferences prefs, String mainKey, String overrideKey) {
    OverridableBoolean ob = prefs.nullSafeGet(mainKey).map(OverridableBoolean::from).orElseGet(OverridableBoolean::empty);
    prefs.nullSafeGet(overrideKey).map(TriStateBoolean::from).ifPresent(ob::overrideWith);
    return ob;
  }

  public static List<String> keys() {
    return keys("");
  }

  public static List<String> keys(String keyPrefix) {
    return Arrays.asList(
        keyPrefix + EXPORT_DIR,
        keyPrefix + PEM_FILE,
        keyPrefix + START_DATE,
        keyPrefix + END_DATE,
        keyPrefix + PULL_BEFORE,
        keyPrefix + OVERWRITE_FILES,
        keyPrefix + EXPORT_MEDIA,
        keyPrefix + SPLIT_SELECT_MULTIPLES
    );
  }

  private static Optional<PrivateKey> readPemFile(Path pemFile) {
    try (InputStream is = newInputStream(pemFile);
         InputStreamReader isr = new InputStreamReader(is, UTF_8);
         BufferedReader br = new BufferedReader(isr);
         PEMReader pr = new PEMReader(br)
    ) {
      Object o = pr.readObject();
      if (o == null) {
        log.warn("The supplied file is not in PEM format");
        return Optional.empty();
      }

      if (o instanceof KeyPair)
        return Optional.of(((KeyPair) o).getPrivate());

      if (o instanceof PrivateKey)
        return Optional.of(((PrivateKey) o));

      log.warn("The supplied file does not contain a private key");
      return Optional.empty();
    } catch (IOException e) {
      throw new BriefcaseException("Briefcase can't read the pem file", e);
    }
  }

  public Map<String, String> asMap() {
    return asMap("");
  }

  public Map<String, String> asMap(String keyPrefix) {
    // This should be a stream of tuples that's reduces into a
    // map but we'll have to wait for that
    HashMap<String, String> map = new HashMap<>();
    exportDir.ifPresent(value -> map.put(keyPrefix + EXPORT_DIR, value.toString()));
    pemFile.ifPresent(value -> map.put(keyPrefix + PEM_FILE, value.toString()));
    dateRange.ifStartPresent(value -> map.put(keyPrefix + START_DATE, value.format(DateTimeFormatter.ISO_DATE)));
    dateRange.ifEndPresent(value -> map.put(keyPrefix + END_DATE, value.format(DateTimeFormatter.ISO_DATE)));
    map.put(keyPrefix + PULL_BEFORE, pullBefore.serialize());
    map.put(keyPrefix + OVERWRITE_FILES, overwriteFiles.serialize());
    map.put(keyPrefix + EXPORT_MEDIA, exportMedia.serialize());
    map.put(keyPrefix + SPLIT_SELECT_MULTIPLES, splitSelectMultiples.serialize());
    return map;
  }

  public Path getExportDir() {
    return exportDir.orElseThrow(BriefcaseException::new);
  }

  public Path getPemFile() {
    return pemFile.orElseThrow(BriefcaseException::new);
  }

  public boolean isPemFilePresent() {
    return pemFile.isPresent();
  }

  public boolean resolvePullBefore() {
    return pullBefore.resolve(false);
  }

  boolean resolveExportMedia() {
    return exportMedia.resolve(true);
  }

  boolean resolveOverwriteExistingFiles() {
    return overwriteFiles.resolve(false);
  }

  boolean resolveSplitSelectMultiples() {
    return splitSelectMultiples.resolve(false);
  }

  public OverridableBoolean getPullBefore() {
    return pullBefore;
  }

  public OverridableBoolean getOverwriteFiles() {
    return overwriteFiles;
  }

  public OverridableBoolean getExportMedia() {
    return exportMedia;
  }

  public OverridableBoolean getSplitSelectMultiples() {
    return splitSelectMultiples;
  }

  public void ifExportDirPresent(Consumer<Path> consumer) {
    exportDir.ifPresent(consumer);
  }

  public void ifPemFilePresent(Consumer<Path> consumer) {
    pemFile.ifPresent(consumer);
  }

  public boolean isEmpty() {
    return !exportDir.isPresent()
        && !pemFile.isPresent()
        && dateRange.isEmpty()
        && pullBefore.isEmpty()
        && overwriteFiles.isEmpty()
        && exportMedia.isEmpty()
        && splitSelectMultiples.isEmpty();
  }

  public boolean isValid() {
    return exportDir.isPresent();
  }

  ExportConfiguration fallingBackTo(ExportConfiguration defaultConfiguration) {
    return new ExportConfiguration(
        exportFileName, exportDir.isPresent() ? exportDir : defaultConfiguration.exportDir,
        pemFile.isPresent() ? pemFile : defaultConfiguration.pemFile,
        !dateRange.isEmpty() ? dateRange : defaultConfiguration.dateRange,
        pullBefore.fallingBackTo(defaultConfiguration.pullBefore),
        overwriteFiles.fallingBackTo(defaultConfiguration.overwriteFiles),
        exportMedia.fallingBackTo(defaultConfiguration.exportMedia),
        splitSelectMultiples.fallingBackTo(defaultConfiguration.splitSelectMultiples)
    );
  }

  Optional<PrivateKey> getPrivateKey() {
    return pemFile.flatMap(ExportConfiguration::readPemFile);
  }

  public DateRange getDateRange() {
    return dateRange;
  }

  Path getExportMediaPath() {
    return exportDir.map(dir -> dir.resolve("media")).orElseThrow(() -> new BriefcaseException("No export dir configured"));
  }

  public String getFilenameBase(String formName) {
    return exportFileName
        .map(UncheckedFiles::stripFileExtension)
        .map(StringUtils::stripIllegalChars)
        .orElse(stripIllegalChars(formName));
  }

  Path getErrorsDir(String formName) {
    return exportDir.map(dir -> dir.resolve(stripIllegalChars(formName) + " - errors")).orElseThrow(BriefcaseException::new);
  }

  Path getAuditPath(String formName) {
    return exportDir.orElseThrow(BriefcaseException::new).resolve(formName + " - audit.csv");
  }

  @Override
  public String toString() {
    return "ExportConfiguration{" +
        "exportDir=" + exportDir +
        ", pemFile=" + pemFile +
        ", dateRange=" + dateRange +
        ", pullBefore=" + pullBefore +
        ", overwriteFiles=" + overwriteFiles +
        ", exportMedia=" + exportMedia +
        ", splitSelectMultiples=" + splitSelectMultiples +
        '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
    ExportConfiguration that = (ExportConfiguration) o;
    return Objects.equals(exportDir, that.exportDir) &&
        Objects.equals(pemFile, that.pemFile) &&
        Objects.equals(dateRange, that.dateRange) &&
        Objects.equals(pullBefore, that.pullBefore) &&
        Objects.equals(overwriteFiles, that.overwriteFiles) &&
        Objects.equals(exportMedia, that.exportMedia) &&
        Objects.equals(splitSelectMultiples, that.splitSelectMultiples);
  }

  @Override
  public int hashCode() {
    return Objects.hash(exportDir, pemFile, dateRange, pullBefore, overwriteFiles, exportMedia, splitSelectMultiples);
  }

}

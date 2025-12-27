package com.dev.idea.plugins.tomcat.utils;

 import com.intellij.openapi.diagnostic.Logger;
 import org.jetbrains.annotations.NotNull;
 import org.jetbrains.annotations.Nullable;

 import java.nio.file.Files;
 import java.nio.file.Path;
 import java.nio.file.Paths;
 import java.util.ArrayList;
 import java.util.Collections;
 import java.util.List;
 import java.util.Objects;

 public final class ValidationUtils {
     private static final Logger LOG = Logger.getInstance(ValidationUtils.class);

     private ValidationUtils() {}

     public static boolean isValidFile(@Nullable String filePath) {
         if (StringUtils.isEmpty(filePath)) return false;
         try {
             Path path = Paths.get(filePath);
             return Files.isRegularFile(path) && Files.isReadable(path);
         } catch (Exception e) {
             LOG.debug("Invalid file path: " + filePath, e);
             return false;
         }
     }

     public static boolean isValidDirectory(@Nullable String dirPath) {
         if (StringUtils.isEmpty(dirPath)) return false;
         try {
             Path path = Paths.get(dirPath);
             return Files.isDirectory(path) && Files.isReadable(path);
         } catch (Exception e) {
             LOG.debug("Invalid directory path: " + dirPath, e);
             return false;
         }
     }

     public static boolean isWritableDirectory(@Nullable String dirPath) {
         if (!isValidDirectory(dirPath)) return false;
         try {
             return Files.isWritable(Paths.get(dirPath));
         } catch (Exception e) {
             return false;
         }
     }

     // Delegate port validation to PortUtils
     public static boolean isValidPort(int port) {
         return PortUtils.isValid(port);
     }

     public static class Result {
         private final List<String> errors = new ArrayList<>();
         private final List<String> warnings = new ArrayList<>();
         private final List<String> suggestions = new ArrayList<>();

         public void addError(@NotNull String error) { errors.add(Objects.requireNonNull(error)); }
         public void addWarning(@NotNull String warning) { warnings.add(Objects.requireNonNull(warning)); }
         public void addSuggestion(@NotNull String suggestion) { suggestions.add(Objects.requireNonNull(suggestion)); }

         public boolean isValid() { return errors.isEmpty(); }
         public boolean hasWarnings() { return !warnings.isEmpty(); }

         @NotNull public List<String> getErrors() { return Collections.unmodifiableList(errors); }
         @NotNull public List<String> getWarnings() { return Collections.unmodifiableList(warnings); }
         @NotNull public List<String> getSuggestions() { return Collections.unmodifiableList(suggestions); }

         @NotNull public String getErrorsAsString() { return String.join("\n", errors); }
     }
 }
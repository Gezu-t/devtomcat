package com.dev.idea.plugins.tomcat.utils;

 import com.intellij.openapi.diagnostic.Logger;
 import org.jetbrains.annotations.Nullable;

 import java.nio.file.Files;
 import java.nio.file.Path;
 import java.nio.file.Paths;

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

     public static boolean isValidPort(int port) {
         return PortUtils.isValid(port);
     }
 }
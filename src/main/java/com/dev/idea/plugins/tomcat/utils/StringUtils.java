package com.dev.idea.plugins.tomcat.utils;

    import org.jetbrains.annotations.NotNull;
    import org.jetbrains.annotations.Nullable;

    import java.io.File;
    import java.util.Collection;
    import java.util.Objects;

    public final class StringUtils {
        public static final String EMPTY = "";
        public static final String PATH_SEPARATOR = File.separator;

        private StringUtils() {}

        @NotNull
        public static String nullToEmpty(@Nullable String str) {
            return str == null ? EMPTY : str;
        }

        @NotNull
        public static String defaultIfEmpty(@Nullable String str, @NotNull String defaultValue) {
            return isEmpty(str) ? Objects.requireNonNull(defaultValue) : str;
        }

        public static boolean isEmpty(@Nullable String str) {
            return str == null || str.isEmpty();
        }

        public static boolean isNotEmpty(@Nullable String str) {
            return !isEmpty(str);
        }

        public static boolean isBlank(@Nullable String str) {
            return str == null || str.trim().isEmpty();
        }

        @NotNull
        public static String trim(@Nullable String str) {
            return str == null ? EMPTY : str.trim();
        }

        @NotNull
        public static String joinPath(@NotNull String... parts) {
            Objects.requireNonNull(parts);
            StringBuilder result = new StringBuilder();
            boolean first = true;
            for (String part : parts) {
                if (isEmpty(part)) continue;
                String clean = part.replaceAll("^[/\\\\]+|[/\\\\]+$", "");
                if (!clean.isEmpty()) {
                    if (!first) result.append(PATH_SEPARATOR);
                    result.append(clean);
                    first = false;
                }
            }
            return result.toString();
        }

        @NotNull
        public static String sanitizeFileName(@Nullable String name) {
            if (isEmpty(name)) return "unnamed";
            return name.trim()
                .replaceAll("[^a-zA-Z0-9._-]", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_|_$", "");
        }

        @NotNull
        public static String join(@Nullable Collection<?> collection, @NotNull String delimiter) {
            Objects.requireNonNull(delimiter);
            if (collection == null || collection.isEmpty()) return EMPTY;
            StringBuilder result = new StringBuilder();
            boolean first = true;
            for (Object item : collection) {
                if (!first) result.append(delimiter);
                result.append(item == null ? "" : item.toString());
                first = false;
            }
            return result.toString();
        }
    }
package com.yandu.workspace.domain;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Project aggregate owns its source locale and the set of configured target locales. */
public final class Project {

    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*");

    private final long id;
    private final long ownerId;
    private final String name;
    private final String sourceLocale;
    private final Set<String> targetLocales;
    private long version;

    public Project(long id, long ownerId, String name, String sourceLocale,
                   Set<String> targetLocales, long version) {
        if (id <= 0 || ownerId <= 0 || version <= 0) {
            throw new IllegalArgumentException("id, ownerId and version must be positive");
        }
        this.id = id;
        this.ownerId = ownerId;
        this.name = requireName(name);
        this.sourceLocale = validateLocale(sourceLocale);
        this.targetLocales = new LinkedHashSet<>();
        for (String locale : targetLocales) {
            var validLocale = validateLocale(locale);
            if (this.sourceLocale.equalsIgnoreCase(validLocale)
                    || !this.targetLocales.add(validLocale)) {
                throw new IllegalArgumentException("target locales must be unique and differ from source locale");
            }
        }
        this.version = version;
    }

    public void addTargetLocale(String locale) {
        var validLocale = validateLocale(locale);
        if (sourceLocale.equalsIgnoreCase(validLocale)) {
            throw new IllegalArgumentException("target locale cannot equal the source locale");
        }
        if (!targetLocales.add(validLocale)) {
            throw new IllegalArgumentException("target locale is already configured: " + validLocale);
        }
        version = Math.incrementExact(version);
    }

    public long id() { return id; }
    public long ownerId() { return ownerId; }
    public String name() { return name; }
    public String sourceLocale() { return sourceLocale; }
    public Set<String> targetLocales() { return Set.copyOf(targetLocales); }
    public long version() { return version; }

    public static String validateLocale(String locale) {
        if (locale == null || !LOCALE.matcher(locale).matches()) {
            throw new IllegalArgumentException("locale must be a valid BCP 47 language tag");
        }
        var parsed = Locale.forLanguageTag(locale);
        if (parsed.getLanguage().isEmpty() || parsed.toLanguageTag().equals("und")) {
            throw new IllegalArgumentException("locale must be a valid BCP 47 language tag");
        }
        return parsed.toLanguageTag();
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 120) {
            throw new IllegalArgumentException("project name must contain 1 to 120 characters");
        }
        return value.strip();
    }
}

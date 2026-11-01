package com.yandu.consumer;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.List;

import org.springframework.context.support.AbstractMessageSource;

/** Resolves Spring messages from the latest verified YANDU snapshot. */
public final class YanduMessageSource extends AbstractMessageSource {

    private final TranslationCatalog catalog;
    private final List<Locale> configuredLocales;

    public YanduMessageSource(TranslationCatalog catalog, List<String> configuredLocales) {
        this.catalog = catalog;
        this.configuredLocales = configuredLocales.stream().map(Locale::forLanguageTag).toList();
        if (this.configuredLocales.isEmpty() || this.configuredLocales.stream().anyMatch(locale -> locale.getLanguage().isBlank())) {
            throw new IllegalArgumentException("A valid YANDU translation locale is required");
        }
    }

    @Override
    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        var matchingLocale = matchingLocale(locale);
        return matchingLocale == null ? null : catalog.find(matchingLocale.toLanguageTag(), code).orElse(null);
    }

    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        String pattern = resolveCodeWithoutArguments(code, locale);
        return pattern == null ? null : createMessageFormat(pattern, locale);
    }

    private Locale matchingLocale(Locale requested) {
        if (requested == null || requested.getLanguage().isBlank()) return null;
        for (var configured : configuredLocales) if (configured.equals(requested)) return configured;
        if (requested.getCountry().isBlank()) {
            var languageMatches = configuredLocales.stream()
                    .filter(configured -> configured.getLanguage().equals(requested.getLanguage())).toList();
            if (languageMatches.size() == 1) return languageMatches.get(0);
        }
        return null;
    }
}

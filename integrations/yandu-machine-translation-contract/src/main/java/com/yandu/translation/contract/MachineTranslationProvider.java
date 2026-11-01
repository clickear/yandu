package com.yandu.translation.contract;

/** Provider port for translating one source string without coupling the domain to a vendor SDK. */
public interface MachineTranslationProvider {

    boolean isConfigured();

    String translate(String sourceText, String sourceLocale, String targetLocale);

    final class ProviderFailureException extends RuntimeException {
        public ProviderFailureException(String message) { super(message); }
    }
}

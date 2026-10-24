package iped.app.ui.insights;

/**
 * Returns the localized text of a key, formatted with the given arguments.
 */
@FunctionalInterface
public interface Localizer {

    String get(String key, Object... args);
}

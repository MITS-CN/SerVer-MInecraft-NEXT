package cn.mits.nekogate.api;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves presentation-name overrides.
 *
 * <p>UUID methods are retained as the original API v1.2 contract. New extensions
 * should prefer the player-name methods; implementations may override them for
 * name-keyed configuration.</p>
 */
public interface DisplayNameService {
    /** Original v1.2 lookup retained for binary and source compatibility. */
    Optional<DisplayName> find(UUID playerId);

    /** Name-keyed lookup. Default keeps older third-party implementations compatible. */
    default Optional<DisplayName> find(String username) {
        return Optional.empty();
    }

    /** Original v1.2 mutation retained for existing extensions. */
    void set(UUID playerId, DisplayName displayName);

    /** Name-keyed runtime override. */
    default void set(String username, DisplayName displayName) {
        throw new UnsupportedOperationException("Name-based display-name updates are not supported");
    }

    /** Original v1.2 mutation retained for existing extensions. */
    void clear(UUID playerId);

    /** Name-keyed runtime override removal. */
    default void clear(String username) {
        // Older implementations have no name-keyed runtime state.
    }

    void reload() throws IOException;

    /** Original v1.2 presentation method retained for existing extensions. */
    Component chatName(UUID playerId, String authenticatedUsername);

    /** Name-keyed presentation method. */
    default Component chatName(String username) {
        return Component.text(username == null ? "" : username);
    }

    /** Original v1.2 presentation method retained for existing extensions. */
    Component tabName(UUID playerId, String authenticatedUsername);

    /** Name-keyed presentation method. */
    default Component tabName(String username) {
        return Component.text(username == null ? "" : username);
    }
}

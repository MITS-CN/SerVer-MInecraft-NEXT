package cn.mits.nekogate.api;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** Resolves configured and runtime presentation-name overrides by player name. */
public interface DisplayNameService {
    Optional<DisplayName> find(String username);
    void set(String username, DisplayName displayName);
    void clear(String username);
    void reload() throws IOException;
    Component chatName(String username);
    Component tabName(String username);

    /**
     * @deprecated Display-name configuration is keyed by player name. Use {@link #find(String)}.
     */
    @Deprecated
    default Optional<DisplayName> find(UUID playerId) {
        return Optional.empty();
    }

    /** @deprecated Use {@link #set(String, DisplayName)}. */
    @Deprecated
    default void set(UUID playerId, DisplayName displayName) {
        throw new UnsupportedOperationException("Use player-name based display-name keys");
    }

    /** @deprecated Use {@link #clear(String)}. */
    @Deprecated
    default void clear(UUID playerId) {
    }

    /** @deprecated Use {@link #chatName(String)}. */
    @Deprecated
    default Component chatName(UUID playerId, String authenticatedUsername) {
        return chatName(authenticatedUsername);
    }

    /** @deprecated Use {@link #tabName(String)}. */
    @Deprecated
    default Component tabName(UUID playerId, String authenticatedUsername) {
        return tabName(authenticatedUsername);
    }
}

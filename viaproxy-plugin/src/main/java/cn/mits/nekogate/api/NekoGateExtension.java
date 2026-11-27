package cn.mits.nekogate.api;

/**
 * Implement this interface and register it through Java ServiceLoader:
 * META-INF/services/cn.mits.nekogate.api.NekoGateExtension
 */
public interface NekoGateExtension {
    /** Called once after the extension JAR has been loaded. */
    void onEnable(NekoGateAPI api) throws Exception;

    /** Called during ViaProxy shutdown. */
    default void onDisable() throws Exception { }
}

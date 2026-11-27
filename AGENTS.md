# Target environment

This printer is developed for 2b2t. The target server is **Minecraft 1.21.4
running Folia**. The `1.21.11` branch builds a **Minecraft 1.21.11 Fabric
client**. Use the server version when reasoning about placement, packet
handling and tick order.

For placement fixes, reproduce the reported behavior with the native printer
against a 1.21.4 Folia server. Record the client build, server build, protocol
translation if used, and the block state the server actually placed. Compare
the old and changed printer on the same setup when judging a regression.
Identify results from vanilla, an integrated server or another Folia version
as comparisons. Distinguish local Folia reproduction from observations on 2b2t
itself, where the proxy, plugins and server settings may differ.

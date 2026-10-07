package fr.eternom.eterLib.listeners;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.MenuListener;
import fr.eternom.eterLib.module.combat.CombatListener;
import fr.eternom.eterLib.module.player.PlayerListener;
import fr.eternom.eterLib.module.server.ServerNameListener;
import fr.eternom.eterLib.module.teleport.TeleportListener;
import org.bukkit.event.Listener;

public class Events {

    public Events(EterLib lib, boolean cancelWarmupOnDamage) {
        register(lib, new PlayerListener(lib, lib.getPlayers()));
        register(lib, new CombatListener(lib.getCombat()));
        register(lib, new TeleportListener(lib, lib.getTeleports(), lib.getWarmup(), cancelWarmupOnDamage));
        register(lib, new MenuListener(lib));
        register(lib, new ServerNameListener(lib, lib.getServerName(), lib.getServerDisplayName()));
    }

    private void register(EterLib lib, Listener listener) {
        lib.getServer().getPluginManager().registerEvents(listener, lib);
    }
}

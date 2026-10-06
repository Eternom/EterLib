package fr.eternom.eterLib.module.combat;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Met en combat la victime et l'attaquant de tout coup entre entités vivantes (joueurs et monstres).
 * Le combat n'est pas effacé à la déconnexion : se reconnecter ne permet pas d'y échapper.
 */
public class CombatListener implements Listener {

    private final CombatTracker combat;

    public CombatListener(CombatTracker combat) {
        this.combat = combat;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity attacker = event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter
                ? shooter
                : event.getDamager();
        if (!(attacker instanceof LivingEntity) || !(event.getEntity() instanceof LivingEntity victim) || victim instanceof ArmorStand) {
            return;
        }
        if (victim instanceof Player player) {
            combat.tag(player.getUniqueId());
        }
        if (attacker instanceof Player player) {
            combat.tag(player.getUniqueId());
        }
    }
}

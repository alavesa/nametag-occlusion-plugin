package fi.alavesa.nametagocclusion;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class NametagOcclusionPlugin extends JavaPlugin {

    private OcclusionService service;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings settings = Settings.load(getConfig(), getLogger());
        service = new OcclusionService(this, settings);
        service.start();
        getServer().getPluginManager().registerEvents(service, this);
        schedule(settings);
        registerCommand("nametagocclusion", "Nametag occlusion: reload, toggle", List.of("nto"), new RootCommand());
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        if (service != null) {
            service.reset();
        }
    }

    // line of sight is checked on the main thread: ray traces read the world
    private void schedule(Settings settings) {
        if (task != null) {
            task.cancel();
        }
        task = getServer().getScheduler().runTaskTimer(this, service, 1L, settings.intervalTicks);
    }

    private void reload() {
        reloadConfig();
        Settings settings = Settings.load(getConfig(), getLogger());
        service.apply(settings);
        schedule(settings);
    }

    private final class RootCommand implements BasicCommand {

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            CommandSender sender = source.getSender();
            String sub = args.length == 0 ? "" : args[0].toLowerCase();
            switch (sub) {
                case "reload" -> {
                    if (!sender.hasPermission("nametagocclusion.reload")) {
                        sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
                        return;
                    }
                    reload();
                    sender.sendMessage(Component.text("NametagOcclusion config reloaded.", NamedTextColor.GREEN));
                }
                case "toggle" -> {
                    if (!sender.hasPermission("nametagocclusion.toggle")) {
                        sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
                        return;
                    }
                    // the executor differs from the sender under /execute as <player>
                    if (!(source.getExecutor() instanceof Player player)) {
                        sender.sendMessage(Component.text("Only a player can toggle their own view.", NamedTextColor.RED));
                        return;
                    }
                    boolean off = service.toggle(player);
                    sender.sendMessage(Component.text(off
                            ? "Nametag occlusion is now OFF for your view: you see tags through blocks."
                            : "Nametag occlusion is now ON for your view.", NamedTextColor.YELLOW));
                }
                default -> sender.sendMessage(Component.text("Usage: /nametagocclusion <reload|toggle>", NamedTextColor.GRAY));
            }
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            List<String> out = new ArrayList<>(2);
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase();
                for (String sub : List.of("reload", "toggle")) {
                    if (sub.startsWith(prefix) && source.getSender().hasPermission("nametagocclusion." + sub)) {
                        out.add(sub);
                    }
                }
            }
            return out;
        }

        @Override
        public boolean canUse(CommandSender sender) {
            return sender.hasPermission("nametagocclusion.reload") || sender.hasPermission("nametagocclusion.toggle");
        }
    }
}

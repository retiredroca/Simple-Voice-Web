package com.retiredroca.svcweb.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.retiredroca.svcweb.SimpleVoiceWeb;
import com.retiredroca.svcweb.VoiceWebCommands;
import com.retiredroca.svcweb.VoiceWebService;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

/** Registers {@code /svcweb}. */
final class VoiceWebCommand {

    private VoiceWebCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                         Supplier<VoiceWebService> service) {
        dispatcher.register(Commands.literal("svcweb")
                .requires(source -> {
                    VoiceWebService current = service.get();
                    return current == null || !current.config().commandRequiresOp
                            || source.permissions().hasPermission(
                                    new net.minecraft.server.permissions.Permission.HasCommandLevel(
                                            net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS));
                })
                .then(Commands.literal("link")
                        .executes(context -> link(context, service)))
                .then(Commands.literal("status")
                        .executes(context -> status(context, service)))
                .then(Commands.literal("revoke")
                        .executes(context -> revoke(context, service)))
                .then(Commands.literal("groups")
                        .executes(context -> groups(context, service)))
                .then(Commands.literal("group")
                        .then(Commands.literal("leave")
                                .executes(context -> leaveGroup(context, service)))
                        .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(context -> joinGroup(context, service)))));
    }

    private static int link(CommandContext<CommandSourceStack> context,
                            Supplier<VoiceWebService> serviceSupplier) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command is for players."));
            return 0;
        }
        VoiceWebService service = serviceSupplier.get();
        if (service == null) {
            context.getSource().sendFailure(Component.literal("Simple Voice Web is not running."));
            return 0;
        }
        VoiceWebCommands commands = new VoiceWebCommands(service);
        String line = commands.link(player.getUUID());
        int marker = line.indexOf("http");
        if (marker < 0) {
            context.getSource().sendSuccess(() -> Component.literal(line), false);
            return 0;
        }
        String url = line.substring(marker).trim();
        Component message = Component.literal(line.substring(0, marker))
                .append(Component.literal(url).withStyle(Style.EMPTY
                        .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url)))
                        .withUnderlined(true)));
        context.getSource().sendSuccess(() -> message, false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context,
                              Supplier<VoiceWebService> serviceSupplier) {
        VoiceWebService service = serviceSupplier.get();
        if (service == null) {
            context.getSource().sendFailure(Component.literal("Simple Voice Web is not running."));
            return 0;
        }
        String text = new VoiceWebCommands(service).status();
        for (String line : text.split("\n")) {
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int groups(CommandContext<CommandSourceStack> context,
                              Supplier<VoiceWebService> serviceSupplier) {
        VoiceWebService service = serviceSupplier.get();
        if (service == null) {
            context.getSource().sendFailure(Component.literal("Simple Voice Web is not running."));
            return 0;
        }
        for (String line : service.listGroups().split("\n")) {
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int joinGroup(CommandContext<CommandSourceStack> context,
                                 Supplier<VoiceWebService> serviceSupplier) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command is for players."));
            return 0;
        }
        VoiceWebService service = serviceSupplier.get();
        if (service == null) {
            context.getSource().sendFailure(Component.literal("Simple Voice Web is not running."));
            return 0;
        }
        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "name");
        String result = service.joinGroup(player.getUUID(), name);
        context.getSource().sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int leaveGroup(CommandContext<CommandSourceStack> context,
                                  Supplier<VoiceWebService> serviceSupplier) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command is for players."));
            return 0;
        }
        VoiceWebService service = serviceSupplier.get();
        if (service == null) {
            context.getSource().sendFailure(Component.literal("Simple Voice Web is not running."));
            return 0;
        }
        String result = service.leaveGroup(player.getUUID());
        context.getSource().sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int revoke(CommandContext<CommandSourceStack> context,
                              Supplier<VoiceWebService> serviceSupplier) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command is for players."));
            return 0;
        }
        VoiceWebService service = serviceSupplier.get();
        if (service == null) {
            context.getSource().sendFailure(Component.literal("Simple Voice Web is not running."));
            return 0;
        }
        service.revoke(player.getUUID());
        context.getSource().sendSuccess(
                () -> Component.literal("Revoked your voice links and sessions."), false);
        return 1;
    }
}

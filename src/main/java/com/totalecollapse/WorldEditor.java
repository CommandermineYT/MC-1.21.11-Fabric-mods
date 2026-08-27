package com.totalecollapse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A WorldEdit-style editor for Totale Collapse. Every command is registered
 * under a literal that itself begins with a slash, so in chat they all read as
 * a double slash: {@code //set}, {@code //sphere}, and so on. Brigadier is
 * perfectly happy with a slash inside a literal name.
 *
 * <p>Three things here that vanilla commands will not do:
 *
 * <ul>
 *   <li><b>No volume cap.</b> {@code /fill} refuses more than 32768 blocks.
 *       Nothing here is capped, because edits are streamed across ticks in
 *       batches of {@link #BLOCKS_PER_TICK} instead of all in one frame.</li>
 *   <li><b>No neighbour updates.</b> Every write uses
 *       {@link Block#UPDATE_CLIENTS} only, so sand does not cascade, water does
 *       not flow, and redstone does not fire while you build. That alone is the
 *       difference between a 2-million-block edit finishing and the server
 *       dying.</li>
 *   <li><b>Undo.</b> Each operation snapshots what it overwrote.</li>
 * </ul>
 */
public final class WorldEditor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("totale-collapse-worldedit");

    /**
     * Blocks written per server tick. Higher finishes sooner but stutters.
     * 20k/tick is roughly 400k blocks per second.
     */
    private static final int BLOCKS_PER_TICK = 20_000;

    /** Per-operation snapshot ceiling. Past this, the edit is not undoable. */
    private static final int MAX_UNDO_BLOCKS = 1_000_000_000;

    /** How many operations back you can undo, per player. */
    private static final int UNDO_DEPTH = 100;

    /** Left-click sets corner one, right-click sets corner two. */
    private static final net.minecraft.world.item.Item WAND_ITEM = Items.GOLDEN_AXE;

    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
    private static final Map<UUID, Clipboard> CLIPBOARDS = new HashMap<>();
    private static final Map<UUID, Deque<UndoStep>> UNDO_HISTORY = new HashMap<>();

    /** Ordered free-form point lists, used by //mountain and //line. */
    private static final Map<UUID, List<BlockPos>> POINTS = new HashMap<>();

    /** Players who have the selection highlight switched on via //hl. */
    private static final Set<UUID> HIGHLIGHTS = new HashSet<>();

    /** How many points a single player may place. */
    private static final int MAX_POINTS = 64;

    /** Ticks between highlight particle refreshes. */
    private static final int HIGHLIGHT_INTERVAL = 8;

    /** Rolling counter driving the highlight refresh. */
    private static int highlightClock;

    private static final Deque<PendingEdit> QUEUE = new ArrayDeque<>();

    /**
     * Every command, its argument shape and what it actually does. //help reads
     * straight from this table, so adding a command here is the only place a
     * description ever needs to be written.
     */
    private static final String[][] COMMAND_HELP = {
        // --- selection ---
        {"selection", "//wand", "",
            "Gives you a golden axe. Left-click a block to set corner 1, right-click to set corner 2, crouch+right-click to drop a free point."},
        {"selection", "//pos1", "",
            "Sets corner 1 of the box selection to the block you are standing on."},
        {"selection", "//pos2", "",
            "Sets corner 2 of the box selection to the block you are standing on."},
        {"selection", "//sel", "",
            "Reports the width, height, depth and total block count of the current selection."},
        {"selection", "//desel", "",
            "Forgets both corners so the next wand click starts a fresh selection."},
        {"selection", "//expand", "<n>",
            "Grows the selection by n blocks on all six sides at once."},
        {"selection", "//contract", "<n>",
            "Shrinks the selection by n blocks on all six sides. Refuses to shrink past a single block."},
        {"selection", "//hl", "",
            "Toggles a particle outline around your selection and points, drawn only for you. Bind a key to this in Controls to flick it on and off."},
        // --- points ---
        {"points", "//point", "",
            "Adds the block you are standing on to your point list. Points are ordered and feed //mountain and //line."},
        {"points", "//points", "",
            "Lists every point you have placed, in order, with its coordinates."},
        {"points", "//unpoint", "",
            "Removes the most recently added point."},
        {"points", "//clearpoints", "",
            "Empties your point list completely."},
        // --- region edits ---
        {"region", "//set", "<block>",
            "Replaces every block inside the selection with the given block, air included."},
        {"region", "//replace", "<from> <to>",
            "Swaps only blocks matching <from> for <to>, leaving everything else untouched."},
        {"region", "//walls", "<block>",
            "Builds the four vertical sides of the selection box and leaves the floor, ceiling and interior alone."},
        {"region", "//outline", "<block>",
            "Draws only the twelve edges of the selection box, like a wireframe frame."},
        {"region", "//hollow", "",
            "Clears every block that is fully enclosed by other blocks, turning a solid mass into a shell."},
        {"region", "//drain", "",
            "Removes all water, lava and other fluids inside the selection."},
        {"region", "//overlay", "<block>",
            "Lays a single layer of the given block on top of every exposed surface inside the selection."},
        {"region", "//naturalize", "",
            "Re-layers the terrain inside the selection: grass on top, three blocks of dirt beneath, stone below that."},
        // --- shapes ---
        {"shapes", "//sphere", "<block> <radius> [hollow]",
            "Builds a sphere centred on you. Add the word hollow for a shell instead of a solid ball."},
        {"shapes", "//dome", "<block> <radius> [hollow]",
            "Builds the top half of a sphere sitting on your feet level. Add hollow for a shell."},
        {"shapes", "//cyl", "<block> <radius> <height>",
            "Builds a solid cylinder rising from your feet."},
        {"shapes", "//cone", "<block> <radius> <height> [hollow]",
            "Builds a cone tapering from a base at your feet up to a point. Add hollow for a shell."},
        {"shapes", "//pyramid", "<block> <size> [hollow]",
            "Builds a square pyramid whose base reaches size blocks out from you in each direction."},
        {"shapes", "//line", "<block> [thickness]",
            "Connects your points in order with a straight thick line. Two points give one beam, more give a connected path."},
        {"shapes", "//mountain", "<block> <spread>",
            "Grows terrain up to your points, treating them as a ridge line. Two points make a single mountainside; three or more make a full range with saddles between the peaks. spread is how many horizontal blocks the slope takes per block of height, so 1 is a cliff and 12 is a gentle hill. Fractal noise is added so the surface is not a smooth cone. Only fills air, so it never eats existing terrain."},
        // --- clipboard ---
        {"clipboard", "//copy", "",
            "Stores the selection in your clipboard, remembering where you stood so paste lines up the same way."},
        {"clipboard", "//paste", "[air]",
            "Places the clipboard relative to where you are now. Add air to paste the empty blocks too."},
        {"clipboard", "//stack", "<count>",
            "Repeats the selection count times in the direction you are facing."},
        {"clipboard", "//move", "<count>",
            "Shifts the selection count blocks in the direction you are facing, clearing the space it came from."},
        // --- history ---
        {"history", "//undo", "",
            "Reverses the last finished operation. Up to " + UNDO_DEPTH + " steps are remembered per player."},
        {"history", "//count", "<block>",
            "Counts how many of the given block sit inside the selection without changing anything."},
        {"history", "//cancel", "",
            "Throws away every operation still waiting in the queue."},
        {"history", "//help", "[page|command]",
            "Shows this list. Pass a page number to page through it, or a command name for the full description of just that one."}
    };

    private static final int HELP_PAGE_SIZE = 8;

    /**
     * 1.21.11 dropped integer permission levels for a PermissionCheck system.
     * LEVEL_GAMEMASTERS is the direct equivalent of the old level 2, which is
     * what vanilla uses for /gamemode, /give and friends.
     */
    private WorldEditor() {
    }

    // ==================================================================
    // Registration
    // ==================================================================
    public static void register() {
        registerWandEvents();
        registerCommands();
    }

    private static void registerWandEvents() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (level.isClientSide() || !holdingWand(player)) {
                return InteractionResult.PASS;
            }

            setCorner((ServerPlayer) player, pos, true);

            // CONSUME cancels the break without triggering an arm swing.
            return InteractionResult.CONSUME;
        });

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !holdingWand(player)) {
                return InteractionResult.PASS;
            }

            setCorner((ServerPlayer) player, hitResult.getBlockPos(), false);

            return InteractionResult.CONSUME;
        });
    }

    private static List<BlockPos> pointsOf(UUID playerId) {
        return POINTS.computeIfAbsent(playerId, key -> new ArrayList<>());
    }

    private static boolean holdingWand(Player player) {
        return player instanceof ServerPlayer
                && player.getMainHandItem().is(WAND_ITEM);
    }

    private static void setCorner(ServerPlayer player, BlockPos pos, boolean first) {
        // Crouching turns the right-click into a free-form point instead of corner 2.
        if (!first && player.isShiftKeyDown()) {
            addPoint(player, pos);
            return;
        }

        Selection selection = SELECTIONS.computeIfAbsent(
                player.getUUID(),
                key -> new Selection()
        );

        if (first) {
            selection.first = pos.immutable();
        } else {
            selection.second = pos.immutable();
        }

        player.sendSystemMessage(Component.literal(
                "Corner " + (first ? "1" : "2") + " set to "
                        + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + describeVolume(selection)
        ));
    }

    private static String describeVolume(Selection selection) {
        if (!selection.isComplete()) {
            return "";
        }

        return " (" + selection.volume() + " blocks)";
    }

    // ==================================================================
    // Commands
    // ==================================================================
    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> {

            // ---- selection ----
            dispatcher.register(Commands.literal("/wand")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> giveWand(context.getSource())));

            dispatcher.register(Commands.literal("/pos1")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> cornerAtFeet(context.getSource(), true)));

            dispatcher.register(Commands.literal("/pos2")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> cornerAtFeet(context.getSource(), false)));

            dispatcher.register(Commands.literal("/sel")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> describeSelection(context.getSource())));

            dispatcher.register(Commands.literal("/desel")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> {
                        SELECTIONS.remove(context.getSource().getPlayerOrException().getUUID());
                        feedback(context.getSource(), "Selection cleared.");
                        return Command.SINGLE_SUCCESS;
                    }));

            dispatcher.register(Commands.literal("/expand")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 4096))
                            .executes(context -> resize(
                                    context.getSource(),
                                    IntegerArgumentType.getInteger(context, "amount")
                            ))));

            dispatcher.register(Commands.literal("/contract")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 4096))
                            .executes(context -> resize(
                                    context.getSource(),
                                    -IntegerArgumentType.getInteger(context, "amount")
                            ))));

            // ---- region fills ----
            dispatcher.register(Commands.literal("/set")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .executes(context -> {
                                BlockState target = BlockStateArgument
                                        .getBlock(context, "block").getState();

                                return regionEdit(
                                        context.getSource(),
                                        "set",
                                        (pos, current) -> current == target ? null : target
                                );
                            })));

            dispatcher.register(Commands.literal("/replace")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("from", BlockStateArgument.block(buildContext))
                            .then(Commands.argument("to", BlockStateArgument.block(buildContext))
                                    .executes(context -> {
                                        Block from = BlockStateArgument
                                                .getBlock(context, "from").getState().getBlock();

                                        BlockState to = BlockStateArgument
                                                .getBlock(context, "to").getState();

                                        return regionEdit(
                                                context.getSource(),
                                                "replace",
                                                (pos, current) -> current.is(from) ? to : null
                                        );
                                    }))));

            dispatcher.register(Commands.literal("/walls")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .executes(context -> {
                                BlockState target = BlockStateArgument
                                        .getBlock(context, "block").getState();

                                Selection selection = requireSelection(context.getSource());
                                BlockPos min = selection.min();
                                BlockPos max = selection.max();

                                return regionEdit(
                                        context.getSource(),
                                        "walls",
                                        (pos, current) -> {
                                            boolean edge = pos.getX() == min.getX()
                                                    || pos.getX() == max.getX()
                                                    || pos.getZ() == min.getZ()
                                                    || pos.getZ() == max.getZ();

                                            return edge ? target : null;
                                        }
                                );
                            })));

            dispatcher.register(Commands.literal("/hollow")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> {
                        Selection selection = requireSelection(context.getSource());
                        BlockPos min = selection.min();
                        BlockPos max = selection.max();

                        BlockState air = Blocks.AIR.defaultBlockState();

                        return regionEdit(
                                context.getSource(),
                                "hollow",
                                (pos, current) -> {
                                    boolean shell = pos.getX() == min.getX()
                                            || pos.getX() == max.getX()
                                            || pos.getY() == min.getY()
                                            || pos.getY() == max.getY()
                                            || pos.getZ() == min.getZ()
                                            || pos.getZ() == max.getZ();

                                    return shell || current.isAir() ? null : air;
                                }
                        );
                    }));

            dispatcher.register(Commands.literal("/drain")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> {
                        BlockState air = Blocks.AIR.defaultBlockState();

                        return regionEdit(
                                context.getSource(),
                                "drain",
                                (pos, current) -> current.getFluidState().isEmpty() ? null : air
                        );
                    }));

            // ---- shapes, centred on the player, no selection needed ----
            dispatcher.register(Commands.literal("/sphere")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1, 512))
                                    .executes(context -> sphere(
                                            context.getSource(),
                                            BlockStateArgument.getBlock(context, "block").getState(),
                                            IntegerArgumentType.getInteger(context, "radius"),
                                            true
                                    ))
                                    .then(Commands.literal("hollow")
                                            .executes(context -> sphere(
                                                    context.getSource(),
                                                    BlockStateArgument.getBlock(context, "block").getState(),
                                                    IntegerArgumentType.getInteger(context, "radius"),
                                                    false
                                            ))))));

            dispatcher.register(Commands.literal("/cyl")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1, 512))
                                    .then(Commands.argument("height", IntegerArgumentType.integer(1, 512))
                                            .executes(context -> cylinder(
                                                    context.getSource(),
                                                    BlockStateArgument.getBlock(context, "block").getState(),
                                                    IntegerArgumentType.getInteger(context, "radius"),
                                                    IntegerArgumentType.getInteger(context, "height")
                                            ))))));

            // ---- clipboard ----
            dispatcher.register(Commands.literal("/copy")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> copy(context.getSource())));

            dispatcher.register(Commands.literal("/paste")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> paste(context.getSource(), false))
                    .then(Commands.literal("air")
                            .executes(context -> paste(context.getSource(), true))));

            dispatcher.register(Commands.literal("/stack")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                            .executes(context -> stack(
                                    context.getSource(),
                                    IntegerArgumentType.getInteger(context, "count")
                            ))));

            dispatcher.register(Commands.literal("/move")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 512))
                            .executes(context -> move(
                                    context.getSource(),
                                    IntegerArgumentType.getInteger(context, "count")
                            ))));

            // ---- history + info ----
            dispatcher.register(Commands.literal("/undo")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> undo(context.getSource())));

            dispatcher.register(Commands.literal("/count")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .executes(context -> count(
                                    context.getSource(),
                                    BlockStateArgument.getBlock(context, "block").getState().getBlock()
                            ))));

            dispatcher.register(Commands.literal("/cancel")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> {
                        int dropped = QUEUE.size();
                        QUEUE.clear();
                        feedback(context.getSource(), "Cancelled " + dropped + " queued operation(s).");
                        return Command.SINGLE_SUCCESS;
                    }));

            // ---- free-form points ----
            dispatcher.register(Commands.literal("/point")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> addPoint(context.getSource())));

            dispatcher.register(Commands.literal("/points")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> listPoints(context.getSource())));

            dispatcher.register(Commands.literal("/unpoint")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> removeLastPoint(context.getSource())));

            dispatcher.register(Commands.literal("/clearpoints")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> clearPoints(context.getSource())));

            // ---- terrain ----
            dispatcher.register(Commands.literal("/mountain")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .then(Commands.argument("spread", IntegerArgumentType.integer(1, 40))
                                    .executes(context -> mountain(
                                            context.getSource(),
                                            BlockStateArgument.getBlock(context, "block").getState(),
                                            IntegerArgumentType.getInteger(context, "spread")
                                    )))));

            dispatcher.register(Commands.literal("/line")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .executes(context -> line(
                                    context.getSource(),
                                    BlockStateArgument.getBlock(context, "block").getState(),
                                    1
                            ))
                            .then(Commands.argument("thickness", IntegerArgumentType.integer(1, 32))
                                    .executes(context -> line(
                                            context.getSource(),
                                            BlockStateArgument.getBlock(context, "block").getState(),
                                            IntegerArgumentType.getInteger(context, "thickness")
                                    )))));

            // ---- extra shapes ----
            dispatcher.register(Commands.literal("/dome")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1, 512))
                                    .executes(context -> dome(
                                            context.getSource(),
                                            BlockStateArgument.getBlock(context, "block").getState(),
                                            IntegerArgumentType.getInteger(context, "radius"),
                                            true
                                    ))
                                    .then(Commands.literal("hollow")
                                            .executes(context -> dome(
                                                    context.getSource(),
                                                    BlockStateArgument.getBlock(context, "block").getState(),
                                                    IntegerArgumentType.getInteger(context, "radius"),
                                                    false
                                            ))))));

            dispatcher.register(Commands.literal("/cone")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .then(Commands.argument("radius", IntegerArgumentType.integer(1, 512))
                                    .then(Commands.argument("height", IntegerArgumentType.integer(1, 512))
                                            .executes(context -> cone(
                                                    context.getSource(),
                                                    BlockStateArgument.getBlock(context, "block").getState(),
                                                    IntegerArgumentType.getInteger(context, "radius"),
                                                    IntegerArgumentType.getInteger(context, "height"),
                                                    true
                                            ))
                                            .then(Commands.literal("hollow")
                                                    .executes(context -> cone(
                                                            context.getSource(),
                                                            BlockStateArgument.getBlock(context, "block").getState(),
                                                            IntegerArgumentType.getInteger(context, "radius"),
                                                            IntegerArgumentType.getInteger(context, "height"),
                                                            false
                                                    )))))));

            dispatcher.register(Commands.literal("/pyramid")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .then(Commands.argument("size", IntegerArgumentType.integer(1, 256))
                                    .executes(context -> pyramid(
                                            context.getSource(),
                                            BlockStateArgument.getBlock(context, "block").getState(),
                                            IntegerArgumentType.getInteger(context, "size"),
                                            true
                                    ))
                                    .then(Commands.literal("hollow")
                                            .executes(context -> pyramid(
                                                    context.getSource(),
                                                    BlockStateArgument.getBlock(context, "block").getState(),
                                                    IntegerArgumentType.getInteger(context, "size"),
                                                    false
                                            ))))));

            // ---- surface work ----
            dispatcher.register(Commands.literal("/overlay")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .executes(context -> overlay(
                                    context.getSource(),
                                    BlockStateArgument.getBlock(context, "block").getState()
                            ))));

            dispatcher.register(Commands.literal("/naturalize")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> naturalize(context.getSource())));

            dispatcher.register(Commands.literal("/outline")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .executes(context -> outline(
                                    context.getSource(),
                                    BlockStateArgument.getBlock(context, "block").getState()
                            ))));

            // ---- highlight toggle ----
            dispatcher.register(Commands.literal("/hl")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> toggleHighlight(context.getSource())));

            dispatcher.register(Commands.literal("/help")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> helpPage(context.getSource(), 1))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1, 32))
                            .executes(context -> helpPage(
                                    context.getSource(),
                                    IntegerArgumentType.getInteger(context, "page")
                            )))
                    .then(Commands.argument("command", StringArgumentType.word())
                            .executes(context -> helpFor(
                                    context.getSource(),
                                    StringArgumentType.getString(context, "command")
                            ))));
        });
    }

    // ==================================================================
    // Selection commands
    // ==================================================================
    private static int giveWand(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        player.getInventory().add(new ItemStack(WAND_ITEM));

        feedback(source, "Wand given. Left-click a block for corner 1, right-click for corner 2.");

        return Command.SINGLE_SUCCESS;
    }

    private static int cornerAtFeet(CommandSourceStack source, boolean first)
            throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();

        setCorner(player, player.blockPosition(), first);

        return Command.SINGLE_SUCCESS;
    }

    private static int describeSelection(CommandSourceStack source) throws CommandSyntaxException {
        Selection selection = requireSelection(source);

        BlockPos min = selection.min();
        BlockPos max = selection.max();

        feedback(source, "Selection "
                + (max.getX() - min.getX() + 1) + " x "
                + (max.getY() - min.getY() + 1) + " x "
                + (max.getZ() - min.getZ() + 1)
                + " = " + selection.volume() + " blocks");

        return Command.SINGLE_SUCCESS;
    }

    private static int resize(CommandSourceStack source, int amount) throws CommandSyntaxException {
        Selection selection = requireSelection(source);

        BlockPos min = selection.min().offset(-amount, -amount, -amount);
        BlockPos max = selection.max().offset(amount, amount, amount);

        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            feedback(source, "Selection would collapse to nothing. Ignored.");
            return 0;
        }

        selection.first = min;
        selection.second = max;

        return describeSelection(source);
    }

    // ==================================================================
    // Shapes
    // ==================================================================
    private static int sphere(
            CommandSourceStack source,
            BlockState target,
            int radius,
            boolean filled
    ) throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        BlockPos centre = player.blockPosition();

        double outerSquared = (radius + 0.5) * (radius + 0.5);
        double innerSquared = (radius - 0.5) * (radius - 0.5);

        return queueEdit(
                source,
                player.level(),
                player.getUUID(),
                "sphere",
                centre.offset(-radius, -radius, -radius),
                centre.offset(radius, radius, radius),
                (pos, current) -> {
                    double dx = pos.getX() - centre.getX();
                    double dy = pos.getY() - centre.getY();
                    double dz = pos.getZ() - centre.getZ();

                    double distanceSquared = dx * dx + dy * dy + dz * dz;

                    if (distanceSquared > outerSquared) {
                        return null;
                    }

                    if (!filled && distanceSquared < innerSquared) {
                        return null;
                    }

                    return target;
                }
        );
    }

    private static int cylinder(
            CommandSourceStack source,
            BlockState target,
            int radius,
            int height
    ) throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        BlockPos base = player.blockPosition();

        double outerSquared = (radius + 0.5) * (radius + 0.5);

        return queueEdit(
                source,
                player.level(),
                player.getUUID(),
                "cylinder",
                base.offset(-radius, 0, -radius),
                base.offset(radius, height - 1, radius),
                (pos, current) -> {
                    double dx = pos.getX() - base.getX();
                    double dz = pos.getZ() - base.getZ();

                    return dx * dx + dz * dz > outerSquared ? null : target;
                }
        );
    }

    // ==================================================================
    // Clipboard
    // ==================================================================
    private static int copy(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.level();

        Selection selection = requireSelection(source);

        BlockPos min = selection.min();
        BlockPos max = selection.max();

        int sizeX = max.getX() - min.getX() + 1;
        int sizeY = max.getY() - min.getY() + 1;
        int sizeZ = max.getZ() - min.getZ() + 1;

        long volume = (long) sizeX * sizeY * sizeZ;

        if (volume > MAX_UNDO_BLOCKS) {
            feedback(source, "Selection is " + volume
                    + " blocks; clipboard limit is " + MAX_UNDO_BLOCKS + ".");
            return 0;
        }

        BlockState[] states = new BlockState[(int) volume];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        int index = 0;

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    cursor.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                    states[index++] = level.getBlockState(cursor);
                }
            }
        }

        CLIPBOARDS.put(
                player.getUUID(),
                new Clipboard(states, sizeX, sizeY, sizeZ, min.subtract(player.blockPosition()))
        );

        feedback(source, "Copied " + volume + " blocks.");

        return Command.SINGLE_SUCCESS;
    }

    private static int paste(CommandSourceStack source, boolean includeAir)
            throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        Clipboard clipboard = CLIPBOARDS.get(player.getUUID());

        if (clipboard == null) {
            feedback(source, "Clipboard is empty. Use //copy first.");
            return 0;
        }

        BlockPos anchor = player.blockPosition().offset(clipboard.offset);

        return pasteAt(source, player, clipboard, anchor, includeAir, "paste");
    }

    private static int pasteAt(
            CommandSourceStack source,
            ServerPlayer player,
            Clipboard clipboard,
            BlockPos anchor,
            boolean includeAir,
            String label
    ) {
        BlockPos max = anchor.offset(
                clipboard.sizeX - 1,
                clipboard.sizeY - 1,
                clipboard.sizeZ - 1
        );

        return queueEdit(
                source,
                player.level(),
                player.getUUID(),
                label,
                anchor,
                max,
                (pos, current) -> {
                    BlockState stored = clipboard.at(
                            pos.getX() - anchor.getX(),
                            pos.getY() - anchor.getY(),
                            pos.getZ() - anchor.getZ()
                    );

                    if (stored == null) {
                        return null;
                    }

                    if (!includeAir && stored.isAir()) {
                        return null;
                    }

                    return stored;
                }
        );
    }

    private static int stack(CommandSourceStack source, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.level();

        Selection selection = requireSelection(source);

        Clipboard clipboard = snapshot(level, selection);

        if (clipboard == null) {
            feedback(source, "Selection is too large to stack (limit "
                    + MAX_UNDO_BLOCKS + " blocks).");
            return 0;
        }

        Direction facing = Direction.getApproximateNearest(player.getLookAngle());

        BlockPos min = selection.min();

        int stepX = facing.getStepX() * clipboard.sizeX;
        int stepY = facing.getStepY() * clipboard.sizeY;
        int stepZ = facing.getStepZ() * clipboard.sizeZ;

        for (int i = 1; i <= count; i++) {
            pasteAt(
                    source,
                    player,
                    clipboard,
                    min.offset(stepX * i, stepY * i, stepZ * i),
                    true,
                    "stack " + i + "/" + count
            );
        }

        feedback(source, "Queued " + count + " copies toward " + facing.getName() + ".");

        return Command.SINGLE_SUCCESS;
    }

    private static int move(CommandSourceStack source, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.level();

        Selection selection = requireSelection(source);

        Clipboard clipboard = snapshot(level, selection);

        if (clipboard == null) {
            feedback(source, "Selection is too large to move (limit "
                    + MAX_UNDO_BLOCKS + " blocks).");
            return 0;
        }

        Direction facing = Direction.getApproximateNearest(player.getLookAngle());

        BlockPos min = selection.min();
        BlockState air = Blocks.AIR.defaultBlockState();

        // Clear first, then write - otherwise an overlapping destination
        // would be erased by the clear.
        queueEdit(
                source,
                level,
                player.getUUID(),
                "move (clear)",
                min,
                selection.max(),
                (pos, current) -> current.isAir() ? null : air
        );

        pasteAt(
                source,
                player,
                clipboard,
                min.offset(
                        facing.getStepX() * count,
                        facing.getStepY() * count,
                        facing.getStepZ() * count
                ),
                true,
                "move (place)"
        );

        feedback(source, "Moving " + count + " blocks toward " + facing.getName() + ".");

        return Command.SINGLE_SUCCESS;
    }

    private static Clipboard snapshot(ServerLevel level, Selection selection) {
        BlockPos min = selection.min();
        BlockPos max = selection.max();

        int sizeX = max.getX() - min.getX() + 1;
        int sizeY = max.getY() - min.getY() + 1;
        int sizeZ = max.getZ() - min.getZ() + 1;

        long volume = (long) sizeX * sizeY * sizeZ;

        if (volume > MAX_UNDO_BLOCKS) {
            return null;
        }

        BlockState[] states = new BlockState[(int) volume];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        int index = 0;

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    cursor.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                    states[index++] = level.getBlockState(cursor);
                }
            }
        }

        return new Clipboard(states, sizeX, sizeY, sizeZ, BlockPos.ZERO);
    }

    // ==================================================================
    // History and info
    // ==================================================================
    private static int undo(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        Deque<UndoStep> history = UNDO_HISTORY.get(player.getUUID());

        if (history == null || history.isEmpty()) {
            feedback(source, "Nothing to undo.");
            return 0;
        }

        UndoStep step = history.pop();

        PendingEdit edit = new PendingEdit(
                step.level,
                player.getUUID(),
                "undo " + step.label,
                step.states.keySet().iterator(),
                null,
                step.states,
                false
        );

        QUEUE.add(edit);

        feedback(source, "Undoing " + step.label + " (" + step.states.size() + " blocks).");

        return Command.SINGLE_SUCCESS;
    }

    private static int count(CommandSourceStack source, Block block) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Selection selection = requireSelection(source);

        PendingEdit edit = new PendingEdit(
                player.level(),
                player.getUUID(),
                "count " + block.getName().getString(),
                positionsOf(selection.min(), selection.max()),
                (pos, current) -> current.is(block) ? current : null,
                null,
                true
        );

        QUEUE.add(edit);

        feedback(source, "Counting across " + selection.volume() + " blocks...");

        return Command.SINGLE_SUCCESS;
    }

    private static int helpPage(CommandSourceStack source, int page) {
        int pages = (COMMAND_HELP.length + HELP_PAGE_SIZE - 1) / HELP_PAGE_SIZE;
        int clamped = Math.min(page, pages);
        int start = (clamped - 1) * HELP_PAGE_SIZE;
        int end = Math.min(start + HELP_PAGE_SIZE, COMMAND_HELP.length);

        source.sendSystemMessage(Component.literal(
                "--- world editor, page " + clamped + " of " + pages
                        + " --- //help <command> for detail"));

        String lastGroup = "";

        for (int i = start; i < end; i++) {
            String[] entry = COMMAND_HELP[i];

            if (!entry[0].equals(lastGroup)) {
                lastGroup = entry[0];
                source.sendSystemMessage(Component.literal("[" + lastGroup + "]"));
            }

            source.sendSystemMessage(Component.literal(
                    "  " + entry[1] + (entry[2].isEmpty() ? "" : " " + entry[2])
                            + "  -  " + shorten(entry[3])));
        }

        return Command.SINGLE_SUCCESS;
    }

    /** First sentence only, so a page stays readable in chat. */
    private static String shorten(String description) {
        int stop = description.indexOf(". ");

        if (stop < 0 || stop > 96) {
            return description.length() <= 100
                    ? description
                    : description.substring(0, 97) + "...";
        }

        return description.substring(0, stop + 1);
    }

    private static int helpFor(CommandSourceStack source, String query) {
        String needle = query.toLowerCase(Locale.ROOT);

        while (needle.startsWith("/")) {
            needle = needle.substring(1);
        }

        for (String[] entry : COMMAND_HELP) {
            if (!entry[1].substring(2).equals(needle)) {
                continue;
            }

            source.sendSystemMessage(Component.literal(
                    "--- " + entry[1] + (entry[2].isEmpty() ? "" : " " + entry[2]) + " ---"));
            source.sendSystemMessage(Component.literal("group: " + entry[0]));

            for (String line : wrap(entry[3], 68)) {
                source.sendSystemMessage(Component.literal(line));
            }

            return Command.SINGLE_SUCCESS;
        }

        feedback(source, "No command called //" + needle + ". Try //help for the list.");

        return 0;
    }

    /** Naive word wrap so long descriptions do not run off one chat line. */
    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String word : text.split(" ")) {
            if (current.length() > 0 && current.length() + 1 + word.length() > width) {
                lines.add(current.toString());
                current.setLength(0);
            }

            if (current.length() > 0) {
                current.append(' ');
            }

            current.append(word);
        }

        if (current.length() > 0) {
            lines.add(current.toString());
        }

        return lines;
    }

    // ==================================================================
    // Free-form points
    // ==================================================================
    private static int addPoint(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        return addPoint(player, player.blockPosition());
    }

    /**
     * Shared by //point and the crouching wand click. Talks to the player
     * directly rather than through a CommandSourceStack, because 1.21.11 removed
     * Entity#createCommandSourceStack and the wand path has no source stack.
     */
    private static int addPoint(ServerPlayer player, BlockPos pos) {
        List<BlockPos> points = pointsOf(player.getUUID());

        if (points.size() >= MAX_POINTS) {
            player.sendSystemMessage(Component.literal(
                    "Point limit of " + MAX_POINTS + " reached. Use //unpoint or //clearpoints."));
            return 0;
        }

        points.add(pos.immutable());

        player.sendSystemMessage(Component.literal("Point " + points.size() + " at "
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));

        return Command.SINGLE_SUCCESS;
    }

    private static int listPoints(CommandSourceStack source) throws CommandSyntaxException {
        List<BlockPos> points = pointsOf(source.getPlayerOrException().getUUID());

        if (points.isEmpty()) {
            feedback(source, "No points placed. Use //point, or crouch+right-click with the wand.");
            return 0;
        }

        source.sendSystemMessage(Component.literal("--- " + points.size() + " point(s) ---"));

        for (int i = 0; i < points.size(); i++) {
            BlockPos pos = points.get(i);

            source.sendSystemMessage(Component.literal("  " + (i + 1) + ": "
                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int removeLastPoint(CommandSourceStack source) throws CommandSyntaxException {
        List<BlockPos> points = pointsOf(source.getPlayerOrException().getUUID());

        if (points.isEmpty()) {
            feedback(source, "No points to remove.");
            return 0;
        }

        points.remove(points.size() - 1);

        feedback(source, points.size() + " point(s) left.");

        return Command.SINGLE_SUCCESS;
    }

    private static int clearPoints(CommandSourceStack source) throws CommandSyntaxException {
        pointsOf(source.getPlayerOrException().getUUID()).clear();

        feedback(source, "Points cleared.");

        return Command.SINGLE_SUCCESS;
    }

    private static List<BlockPos> requirePoints(CommandSourceStack source, int minimum)
            throws CommandSyntaxException {

        List<BlockPos> points = pointsOf(source.getPlayerOrException().getUUID());

        if (points.size() < minimum) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.literal("Needs at least " + minimum
                            + " point(s). Place them with //point or crouch+right-click the wand.")
            ).create();
        }

        return points;
    }

    // ==================================================================
    // Terrain generation
    // ==================================================================
    /**
     * Grows terrain up to the player's point list. Points are treated as a
     * ridge line rather than as isolated peaks: for every column the code finds
     * the closest position along the polyline joining consecutive points, then
     * drops away from the interpolated ridge height at the requested slope.
     * Two points therefore give one clean mountainside, and three or more give a
     * range with saddles where the segments meet, which is what you actually
     * want for a mountainside rather than a bag of separate cones.
     */
    private static int mountain(CommandSourceStack source, BlockState target, int spread)
            throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        List<BlockPos> points = new ArrayList<>(requirePoints(source, 1));

        double slope = 1.0D / spread;

        int highest = Integer.MIN_VALUE;
        int lowest = Integer.MAX_VALUE;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (BlockPos pos : points) {
            highest = Math.max(highest, pos.getY());
            lowest = Math.min(lowest, pos.getY());
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        int base = lowest - 24;
        double noiseAmplitude = 2.0D + (highest - base) * 0.10D;

        // How far out the slope can still reach before it drops below the base.
        int pad = Math.min(220, (int) Math.ceil((highest - base) * spread) + 6);

        final List<BlockPos> ridge = points;
        final double amplitude = noiseAmplitude;
        final Map<Long, Integer> cache = new HashMap<>();

        return queueEdit(
                source,
                player.level(),
                player.getUUID(),
                "mountain",
                new BlockPos(minX - pad, base, minZ - pad),
                new BlockPos(maxX + pad, highest + (int) Math.ceil(noiseAmplitude) + 2, maxZ + pad),
                (pos, current) -> {
                    if (!current.isAir()) {
                        return null;
                    }

                    long key = ((long) pos.getX() << 32) ^ (pos.getZ() & 0xffffffffL);

                    Integer top = cache.get(key);

                    if (top == null) {
                        top = ridgeHeight(ridge, slope, amplitude, pos.getX(), pos.getZ());
                        cache.put(key, top);
                    }

                    return pos.getY() <= top ? target : null;
                }
        );
    }

    /** Interpolated ridge height for one column, with fractal noise on top. */
    private static int ridgeHeight(
            List<BlockPos> points,
            double slope,
            double amplitude,
            int x,
            int z
    ) {
        double best = Double.NEGATIVE_INFINITY;

        if (points.size() == 1) {
            BlockPos only = points.get(0);

            double dx = x - only.getX();
            double dz = z - only.getZ();

            best = only.getY() - Math.sqrt(dx * dx + dz * dz) * slope;
        } else {
            for (int i = 0; i < points.size() - 1; i++) {
                BlockPos a = points.get(i);
                BlockPos b = points.get(i + 1);

                double ax = a.getX();
                double az = a.getZ();
                double vx = b.getX() - ax;
                double vz = b.getZ() - az;

                double lengthSquared = vx * vx + vz * vz;

                double t = lengthSquared == 0.0D
                        ? 0.0D
                        : ((x - ax) * vx + (z - az) * vz) / lengthSquared;

                t = Math.max(0.0D, Math.min(1.0D, t));

                double dx = x - (ax + vx * t);
                double dz = z - (az + vz * t);

                double peak = a.getY() + (b.getY() - a.getY()) * t;

                best = Math.max(best, peak - Math.sqrt(dx * dx + dz * dz) * slope);
            }
        }

        double coarse = (fractalNoise(x * 0.045D, z * 0.045D) - 0.5D) * 2.0D * amplitude;
        double fine = (fractalNoise(x * 0.17D, z * 0.17D) - 0.5D) * 2.0D * amplitude * 0.3D;

        return (int) Math.floor(best + coarse + fine);
    }

    /** Deterministic hash noise in [0, 1). No world seed needed. */
    private static double hashNoise(int x, int z) {
        long h = x * 341873128712L + z * 132897987541L;

        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;

        return (h >>> 11) / (double) (1L << 53);
    }

    private static double smoothNoise(double x, double z) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);

        double fx = x - xi;
        double fz = z - zi;

        double sx = fx * fx * (3.0D - 2.0D * fx);
        double sz = fz * fz * (3.0D - 2.0D * fz);

        double n00 = hashNoise(xi, zi);
        double n10 = hashNoise(xi + 1, zi);
        double n01 = hashNoise(xi, zi + 1);
        double n11 = hashNoise(xi + 1, zi + 1);

        return (n00 * (1.0D - sx) + n10 * sx) * (1.0D - sz)
                + (n01 * (1.0D - sx) + n11 * sx) * sz;
    }

    private static double fractalNoise(double x, double z) {
        double sum = 0.0D;
        double amplitude = 1.0D;
        double frequency = 1.0D;
        double total = 0.0D;

        for (int octave = 0; octave < 4; octave++) {
            sum += smoothNoise(x * frequency, z * frequency) * amplitude;
            total += amplitude;
            amplitude *= 0.5D;
            frequency *= 2.0D;
        }

        return sum / total;
    }

    // ==================================================================
    // Extra shapes
    // ==================================================================
    private static int line(CommandSourceStack source, BlockState target, int thickness)
            throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        final List<BlockPos> points = new ArrayList<>(requirePoints(source, 2));

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (BlockPos pos : points) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        final double reach = thickness + 0.5D;
        final double reachSquared = reach * reach;

        return queueEdit(
                source,
                player.level(),
                player.getUUID(),
                "line",
                new BlockPos(minX - thickness, minY - thickness, minZ - thickness),
                new BlockPos(maxX + thickness, maxY + thickness, maxZ + thickness),
                (pos, current) -> {
                    for (int i = 0; i < points.size() - 1; i++) {
                        if (distanceToSegmentSquared(pos, points.get(i), points.get(i + 1))
                                <= reachSquared) {
                            return target;
                        }
                    }

                    return null;
                }
        );
    }

    private static double distanceToSegmentSquared(BlockPos pos, BlockPos a, BlockPos b) {
        double vx = b.getX() - a.getX();
        double vy = b.getY() - a.getY();
        double vz = b.getZ() - a.getZ();

        double lengthSquared = vx * vx + vy * vy + vz * vz;

        double t = lengthSquared == 0.0D
                ? 0.0D
                : ((pos.getX() - a.getX()) * vx
                    + (pos.getY() - a.getY()) * vy
                    + (pos.getZ() - a.getZ()) * vz) / lengthSquared;

        t = Math.max(0.0D, Math.min(1.0D, t));

        double dx = pos.getX() - (a.getX() + vx * t);
        double dy = pos.getY() - (a.getY() + vy * t);
        double dz = pos.getZ() - (a.getZ() + vz * t);

        return dx * dx + dy * dy + dz * dz;
    }

    private static int dome(
            CommandSourceStack source,
            BlockState target,
            int radius,
            boolean filled
    ) throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        BlockPos centre = player.blockPosition();

        double outerSquared = (radius + 0.5D) * (radius + 0.5D);
        double innerSquared = (radius - 0.5D) * (radius - 0.5D);

        return queueEdit(
                source,
                player.level(),
                player.getUUID(),
                "dome",
                centre.offset(-radius, 0, -radius),
                centre.offset(radius, radius, radius),
                (pos, current) -> {
                    double dx = pos.getX() - centre.getX();
                    double dy = pos.getY() - centre.getY();
                    double dz = pos.getZ() - centre.getZ();

                    if (dy < 0.0D) {
                        return null;
                    }

                    double distanceSquared = dx * dx + dy * dy + dz * dz;

                    if (distanceSquared > outerSquared) {
                        return null;
                    }

                    if (!filled && distanceSquared < innerSquared) {
                        return null;
                    }

                    return target;
                }
        );
    }

    private static int cone(
            CommandSourceStack source,
            BlockState target,
            int radius,
            int height,
            boolean filled
    ) throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        BlockPos base = player.blockPosition();

        return queueEdit(
                source,
                player.level(),
                player.getUUID(),
                "cone",
                base.offset(-radius, 0, -radius),
                base.offset(radius, height - 1, radius),
                (pos, current) -> {
                    int dy = pos.getY() - base.getY();

                    double taper = 1.0D - (double) dy / height;
                    double ring = radius * taper;

                    if (ring < 0.0D) {
                        return null;
                    }

                    double dx = pos.getX() - base.getX();
                    double dz = pos.getZ() - base.getZ();

                    double distanceSquared = dx * dx + dz * dz;
                    double outer = ring + 0.5D;

                    if (distanceSquared > outer * outer) {
                        return null;
                    }

                    if (filled) {
                        return target;
                    }

                    // Shell: keep the skin plus the base disc and the tip.
                    double inner = ring - 1.0D;

                    if (dy == 0 || dy == height - 1 || inner <= 0.0D) {
                        return target;
                    }

                    return distanceSquared < inner * inner ? null : target;
                }
        );
    }

    private static int pyramid(
            CommandSourceStack source,
            BlockState target,
            int size,
            boolean filled
    ) throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        BlockPos base = player.blockPosition();

        return queueEdit(
                source,
                player.level(),
                player.getUUID(),
                "pyramid",
                base.offset(-size, 0, -size),
                base.offset(size, size, size),
                (pos, current) -> {
                    int dy = pos.getY() - base.getY();
                    int half = size - dy;

                    if (half < 0) {
                        return null;
                    }

                    int dx = Math.abs(pos.getX() - base.getX());
                    int dz = Math.abs(pos.getZ() - base.getZ());
                    int ring = Math.max(dx, dz);

                    if (ring > half) {
                        return null;
                    }

                    if (filled) {
                        return target;
                    }

                    return dy == 0 || ring == half || ring == half - 1 ? target : null;
                }
        );
    }

    // ==================================================================
    // Surface work
    // ==================================================================
    private static int overlay(CommandSourceStack source, BlockState target)
            throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        final ServerLevel level = player.level();

        return regionEdit(source, "overlay", (pos, current) -> {
            if (!current.isAir()) {
                return null;
            }

            if (level.getBlockState(pos.below()).isAir()) {
                return null;
            }

            return level.getBlockState(pos.above()).isAir() ? target : null;
        });
    }

    private static int naturalize(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        final ServerLevel level = player.level();

        final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        final BlockState dirt = Blocks.DIRT.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();

        return regionEdit(source, "naturalize", (pos, current) -> {
            if (current.isAir() || !current.getFluidState().isEmpty()) {
                return null;
            }

            int depth = 0;

            for (int step = 1; step <= 4; step++) {
                BlockState above = level.getBlockState(pos.above(step));

                if (above.isAir() || !above.getFluidState().isEmpty()) {
                    break;
                }

                depth++;
            }

            if (depth == 0) {
                return grass;
            }

            return depth <= 3 ? dirt : stone;
        });
    }

    private static int outline(CommandSourceStack source, BlockState target)
            throws CommandSyntaxException {

        Selection selection = requireSelection(source);

        final BlockPos min = selection.min();
        final BlockPos max = selection.max();

        return regionEdit(source, "outline", (pos, current) -> {
            int onFace = 0;

            if (pos.getX() == min.getX() || pos.getX() == max.getX()) {
                onFace++;
            }

            if (pos.getY() == min.getY() || pos.getY() == max.getY()) {
                onFace++;
            }

            if (pos.getZ() == min.getZ() || pos.getZ() == max.getZ()) {
                onFace++;
            }

            return onFace >= 2 ? target : null;
        });
    }

    // ==================================================================
    // Selection highlight
    // ==================================================================
    /**
     * Fabric API dropped WorldRenderEvents for 1.21.9+, so there is no supported
     * hook for drawing an outline mesh in the world any more. This does the same
     * job with forced particles sent only to the toggling player, which needs no
     * rendering code, survives future render rewrites, and works in multiplayer
     * without every client needing the mod.
     */
    private static int toggleHighlight(CommandSourceStack source) throws CommandSyntaxException {
        UUID playerId = source.getPlayerOrException().getUUID();

        if (HIGHLIGHTS.remove(playerId)) {
            feedback(source, "Selection highlight off.");
        } else {
            HIGHLIGHTS.add(playerId);
            feedback(source, "Selection highlight on.");
        }

        return Command.SINGLE_SUCCESS;
    }

    private static void drawHighlights(net.minecraft.server.MinecraftServer server) {
        if (HIGHLIGHTS.isEmpty()) {
            return;
        }

        for (UUID playerId : HIGHLIGHTS) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);

            if (player == null) {
                continue;
            }

            Selection selection = SELECTIONS.get(playerId);

            if (selection != null && selection.isComplete()) {
                drawBoxOutline(player, selection.min(), selection.max());
            }

            for (BlockPos pos : pointsOf(playerId)) {
                drawMarker(player, pos);
            }
        }
    }

    private static void drawBoxOutline(ServerPlayer player, BlockPos min, BlockPos max) {
        ServerLevel level = player.level();

        double x0 = min.getX();
        double y0 = min.getY();
        double z0 = min.getZ();
        double x1 = max.getX() + 1.0D;
        double y1 = max.getY() + 1.0D;
        double z1 = max.getZ() + 1.0D;

        // 12 edges, sampled at most 20 times each so a huge selection stays cheap.
        for (int corner = 0; corner < 4; corner++) {
            double cx = (corner & 1) == 0 ? x0 : x1;
            double cz = (corner & 2) == 0 ? z0 : z1;

            traceEdge(level, player, cx, y0, cz, cx, y1, cz);
        }

        for (int corner = 0; corner < 4; corner++) {
            double cy = (corner & 1) == 0 ? y0 : y1;
            double cz = (corner & 2) == 0 ? z0 : z1;

            traceEdge(level, player, x0, cy, cz, x1, cy, cz);
        }

        for (int corner = 0; corner < 4; corner++) {
            double cx = (corner & 1) == 0 ? x0 : x1;
            double cy = (corner & 2) == 0 ? y0 : y1;

            traceEdge(level, player, cx, cy, z0, cx, cy, z1);
        }
    }

    private static void traceEdge(
            ServerLevel level,
            ServerPlayer player,
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1
    ) {
        double length = Math.sqrt(
                (x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));

        int steps = Math.max(1, Math.min(20, (int) Math.round(length)));

        for (int step = 0; step <= steps; step++) {
            double t = (double) step / steps;

            level.sendParticles(
                    player,
                    ParticleTypes.END_ROD,
                    true,
                    false,
                    x0 + (x1 - x0) * t,
                    y0 + (y1 - y0) * t,
                    z0 + (z1 - z0) * t,
                    1,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D
            );
        }
    }

    private static void drawMarker(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();

        for (int step = 0; step < 5; step++) {
            level.sendParticles(
                    player,
                    ParticleTypes.HAPPY_VILLAGER,
                    true,
                    false,
                    pos.getX() + 0.5D,
                    pos.getY() + 0.5D + step * 0.45D,
                    pos.getZ() + 0.5D,
                    1,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D
            );
        }
    }

    // ==================================================================
    // Edit plumbing
    // ==================================================================
    private static int regionEdit(CommandSourceStack source, String label, EditRule rule)
            throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        Selection selection = requireSelection(source);

        return queueEdit(
                source,
                player.level(),
                player.getUUID(),
                label,
                selection.min(),
                selection.max(),
                rule
        );
    }

    private static int queueEdit(
            CommandSourceStack source,
            ServerLevel level,
            UUID playerId,
            String label,
            BlockPos min,
            BlockPos max,
            EditRule rule
    ) {
        int minY = Math.max(min.getY(), level.getMinY());
        int maxY = Math.min(max.getY(), level.getMaxY());

        if (minY > maxY) {
            feedback(source, "Region is entirely outside the world height limits.");
            return 0;
        }

        BlockPos clampedMin = new BlockPos(min.getX(), minY, min.getZ());
        BlockPos clampedMax = new BlockPos(max.getX(), maxY, max.getZ());

        QUEUE.add(new PendingEdit(
                level,
                playerId,
                label,
                positionsOf(clampedMin, clampedMax),
                rule,
                new HashMap<>(),
                false
        ));

        long volume = (long) (clampedMax.getX() - clampedMin.getX() + 1)
                * (clampedMax.getY() - clampedMin.getY() + 1)
                * (clampedMax.getZ() - clampedMin.getZ() + 1);

        feedback(source, "Queued " + label + " over " + volume + " blocks.");

        return Command.SINGLE_SUCCESS;
    }

    private static Iterator<BlockPos> positionsOf(BlockPos min, BlockPos max) {
        return BlockPos.betweenClosedStream(min, max)
                .map(BlockPos::immutable)
                .iterator();
    }

    /**
     * Streams queued edits. Called once per server tick from the mod's main
     * tick handler, and does at most {@link #BLOCKS_PER_TICK} writes in total
     * regardless of how many operations are queued.
     */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (++highlightClock >= HIGHLIGHT_INTERVAL) {
            highlightClock = 0;
            drawHighlights(server);
        }

        int budget = BLOCKS_PER_TICK;

        while (budget > 0 && !QUEUE.isEmpty()) {
            PendingEdit edit = QUEUE.peek();

            budget -= edit.advance(budget);

            if (!edit.positions.hasNext()) {
                QUEUE.poll();
                edit.finish();
            }
        }
    }

    public static void clearAll() {
        POINTS.clear();
        HIGHLIGHTS.clear();
        highlightClock = 0;
        SELECTIONS.clear();
        CLIPBOARDS.clear();
        UNDO_HISTORY.clear();
        QUEUE.clear();
    }

    private static Selection requireSelection(CommandSourceStack source)
            throws CommandSyntaxException {

        Selection selection = SELECTIONS.get(source.getPlayerOrException().getUUID());

        if (selection == null || !selection.isComplete()) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.literal("Set both corners first with //wand or //pos1 and //pos2.")
            ).create();
        }

        return selection;
    }

    private static void feedback(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    // ==================================================================
    // Types
    // ==================================================================
    @FunctionalInterface
    private interface EditRule {

        /** Returns the state to write, or null to leave this block alone. */
        BlockState apply(BlockPos pos, BlockState current);
    }

    private static final class Selection {

        private BlockPos first;
        private BlockPos second;

        private boolean isComplete() {
            return first != null && second != null;
        }

        private BlockPos min() {
            return new BlockPos(
                    Math.min(first.getX(), second.getX()),
                    Math.min(first.getY(), second.getY()),
                    Math.min(first.getZ(), second.getZ())
            );
        }

        private BlockPos max() {
            return new BlockPos(
                    Math.max(first.getX(), second.getX()),
                    Math.max(first.getY(), second.getY()),
                    Math.max(first.getZ(), second.getZ())
            );
        }

        private long volume() {
            BlockPos min = min();
            BlockPos max = max();

            return (long) (max.getX() - min.getX() + 1)
                    * (max.getY() - min.getY() + 1)
                    * (max.getZ() - min.getZ() + 1);
        }
    }

    private static final class Clipboard {

        private final BlockState[] states;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final BlockPos offset;

        private Clipboard(BlockState[] states, int sizeX, int sizeY, int sizeZ, BlockPos offset) {
            this.states = states;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.offset = offset;
        }

        private BlockState at(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
                return null;
            }

            return states[(y * sizeZ + z) * sizeX + x];
        }
    }

    private static final class UndoStep {

        private final ServerLevel level;
        private final String label;
        private final Map<BlockPos, BlockState> states;

        private UndoStep(ServerLevel level, String label, Map<BlockPos, BlockState> states) {
            this.level = level;
            this.label = label;
            this.states = states;
        }
    }

    private static final class PendingEdit {

        private final ServerLevel level;
        private final UUID playerId;
        private final String label;
        private final Iterator<BlockPos> positions;

        /** Null when replaying an undo, since the snapshot supplies the states. */
        private final EditRule rule;

        /**
         * When {@link #rule} is null this holds the states to restore. When
         * {@link #rule} is present this accumulates what was overwritten.
         */
        private final Map<BlockPos, BlockState> snapshot;

        private final boolean readOnly;

        private boolean undoable = true;
        private int changed;

        private PendingEdit(
                ServerLevel level,
                UUID playerId,
                String label,
                Iterator<BlockPos> positions,
                EditRule rule,
                Map<BlockPos, BlockState> snapshot,
                boolean readOnly
        ) {
            this.level = level;
            this.playerId = playerId;
            this.label = label;
            this.positions = positions;
            this.rule = rule;
            this.snapshot = snapshot;
            this.readOnly = readOnly;
        }

        /** Processes up to {@code budget} positions. Returns how many it used. */
        private int advance(int budget) {
            int used = 0;

            while (used < budget && positions.hasNext()) {
                BlockPos pos = positions.next();

                used++;

                BlockState current = level.getBlockState(pos);

                BlockState next = rule == null
                        ? snapshot.get(pos)
                        : rule.apply(pos, current);

                if (next == null || next == current) {
                    continue;
                }

                if (readOnly) {
                    changed++;
                    continue;
                }

                // Record before overwriting, but only for forward edits.
                if (rule != null) {
                    if (snapshot.size() < MAX_UNDO_BLOCKS) {
                        snapshot.put(pos, current);
                    } else {
                        undoable = false;
                    }
                }

                // UPDATE_CLIENTS only: no neighbour ticks, no physics cascade.
                // This is what makes million-block edits survivable.
                level.setBlock(pos, next, Block.UPDATE_CLIENTS);

                changed++;
            }

            return Math.max(1, used);
        }

        private void finish() {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);

            if (rule != null && !readOnly && undoable && !snapshot.isEmpty()) {
                Deque<UndoStep> history = UNDO_HISTORY.computeIfAbsent(
                        playerId,
                        key -> new ArrayDeque<>()
                );

                history.push(new UndoStep(level, label, snapshot));

                while (history.size() > UNDO_DEPTH) {
                    history.removeLast();
                }
            }

            if (player == null) {
                return;
            }

            String suffix = readOnly
                    ? " matched " + changed + " block(s)."
                    : " affected " + changed + " block(s)."
                    + (undoable ? "" : " Too large to undo.");

            player.sendSystemMessage(Component.literal(label + suffix));

            LOGGER.info("{} finished {} ({} blocks)", playerId, label, changed);
        }
    }
}

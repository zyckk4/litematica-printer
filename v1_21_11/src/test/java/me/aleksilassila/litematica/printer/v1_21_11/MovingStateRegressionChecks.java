package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.guides.interaction.CycleStateGuide;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Exercises moving toggle dispatch, packets, confirmations and movement-to-still handoffs. */
public final class MovingStateRegressionChecks {
    private static int assertions;

    public static int run() throws Exception {
        assertions = 0;
        try (StateInteractionTestFixture f = new StateInteractionTestFixture()) {
            for (Block block : new Block[]{Blocks.OAK_TRAPDOOR, Blocks.OAK_DOOR, Blocks.OAK_FENCE_GATE, Blocks.LEVER}) {
                for (boolean initial : new boolean[]{false, true}) checkToggle(f, block, initial);
            }
            checkIronExcluded(f);
            checkGateRevalidation(f);
            checkUserInputAndInventory(f);
            checkDoorHalves(f);
            checkSchedulingPriority(f);
        }
        return assertions;
    }

    private static void checkToggle(StateInteractionTestFixture f, Block block, boolean initial) throws Exception {
        f.reset();
        BooleanProperty property = block instanceof LeverBlock ? LeverBlock.POWERED : Properties.OPEN;
        BlockState before = block.getDefaultState().with(property, initial);
        if (block instanceof FenceGateBlock) before = before.with(FenceGateBlock.FACING, Direction.SOUTH);
        BlockState desired = before.with(property, !initial);
        f.target(before, desired);
        if (block instanceof DoorBlock) putUpperHalf(f, before, desired);
        ItemStack offhand = f.player.offhand;
        float yaw = f.player.getYaw();
        MovingPrinterAction action = MovingPrinterAction.prepare(f.state(StateInteractionTestFixture.TARGET), f.player, 4.5);
        check(action != null, "Moving toggle not prepared: " + block + " initial=" + initial);
        check(action.send(f.client, f.player), "Moving toggle not sent: " + block);
        check(f.network.blockPackets.size() == 1 && f.interaction.blockUses == 0,
                "Toggle must send one server packet without a local interaction prediction");
        var packet = f.network.blockPackets.getFirst();
        check(packet.getHand() == Hand.MAIN_HAND && packet.getBlockHitResult().getBlockPos().equals(StateInteractionTestFixture.TARGET),
                "Moving toggle packet targeted another hand or block");
        check(f.world.getBlockState(StateInteractionTestFixture.TARGET) == before, "Toggle predicted world state locally");
        check(f.player.offhand == offhand && offhand.getCount() == 12 && f.player.getYaw() == yaw,
                "Moving toggle changed offhand or camera");
        check(f.network.inputs.isEmpty(), "Moving toggle rewrote user input");
        check(Printer.hasPendingPlacement(StateInteractionTestFixture.TARGET), "Toggle did not await a server state");
        check(!action.send(f.client, f.player), "Queued toggle clicked twice before confirmation");
        Printer.confirmNativePlacement(StateInteractionTestFixture.TARGET, before);
        check(Printer.hasPendingPlacement(StateInteractionTestFixture.TARGET), "An unchanged update released the toggle");
        for (int i = 0; i < 3; i++) {
            f.printer.onGameTick(); f.printer.flushNativePlacements();
        }
        check(f.network.blockPackets.size() == 1, "Moving rescan toggled before confirmation");
        f.world.blocks.put(StateInteractionTestFixture.TARGET, desired);
        Printer.confirmNativePlacement(StateInteractionTestFixture.TARGET, desired);
        check(!Printer.hasPendingPlacement(StateInteractionTestFixture.TARGET), "Matching state did not confirm toggle");
        if (block instanceof DoorBlock) {
            BlockPos upper = StateInteractionTestFixture.TARGET.up();
            BlockState upperDesired = desired.with(DoorBlock.HALF, DoubleBlockHalf.UPPER);
            f.world.blocks.put(upper, upperDesired);
            Printer.confirmNativePlacement(upper, upperDesired);
        }
        check(MovingPrinterAction.prepare(f.state(StateInteractionTestFixture.TARGET), f.player, 4.5) == null,
                "Completed toggle remained eligible");
    }

    private static void checkIronExcluded(StateInteractionTestFixture f) throws Exception {
        for (Block block : new Block[]{Blocks.IRON_TRAPDOOR, Blocks.IRON_DOOR}) {
            f.reset();
            BlockState before = block.getDefaultState(), desired = before.with(Properties.OPEN, true);
            f.target(before, desired);
            check(CycleStateGuide.tuningProperty(before) == null
                            && MovingPrinterAction.prepare(f.state(StateInteractionTestFixture.TARGET), f.player, 4.5) == null,
                    "Iron door/trapdoor was scheduled for manual opening");
        }
    }

    private static void checkGateRevalidation(StateInteractionTestFixture f) throws Exception {
        f.reset();
        BlockState before = Blocks.OAK_FENCE_GATE.getDefaultState().with(FenceGateBlock.FACING, Direction.SOUTH);
        f.target(before, before.with(FenceGateBlock.OPEN, true));
        MovingPrinterAction action = MovingPrinterAction.prepare(f.state(StateInteractionTestFixture.TARGET), f.player, 4.5);
        check(action != null, "Safe gate interaction not prepared");
        f.setServerYaw(180);
        check(!action.send(f.client, f.player) && f.network.blockPackets.isEmpty(),
                "Gate ignored a changed server yaw that would flip its facing");
        check(f.player.getYaw() == 0 && f.world.getBlockState(StateInteractionTestFixture.TARGET) == before,
                "Rejected gate interaction changed camera or state");
        f.setServerYaw(0); f.player.setYaw(180);
        check(!action.send(f.client, f.player), "Gate ignored a changed camera yaw");
        f.setYaw(0);
        check(action.send(f.client, f.player), "Gate failed to resume at a safe orientation");
    }

    private static void checkUserInputAndInventory(StateInteractionTestFixture f) throws Exception {
        f.reset();
        BlockState before = Blocks.OAK_TRAPDOOR.getDefaultState();
        f.target(before, before.with(Properties.OPEN, true));
        MovingPrinterAction action = MovingPrinterAction.prepare(f.state(StateInteractionTestFixture.TARGET), f.player, 4.5);
        check(action != null, "Trapdoor fixture failed to prepare");
        f.player.sneaking = true;
        check(!action.send(f.client, f.player), "Queued toggle ignored actual sneak state");
        check(MovingPrinterAction.prepare(f.state(StateInteractionTestFixture.TARGET), f.player, 4.5) == null,
                "Sneaking user obtained a moving toggle");
        f.player.sneaking = false;
        f.client.options.sneakKey.setPressed(true);
        check(!action.send(f.client, f.player), "Queued toggle ignored physical sneak key");
        f.client.options.sneakKey.setPressed(false);
        f.setLastInput(new PlayerInput(false, false, false, false, false, true, false));
        check(!action.send(f.client, f.player), "Queued toggle ignored last declared sneak input");
        f.setLastInput(PlayerInput.DEFAULT);
        f.fill(new ItemStack(Items.STONE));
        check(!action.send(f.client, f.player), "Full inventory was cleared to manufacture an empty hand");
        check(f.network.blockPackets.isEmpty() && f.network.inputs.isEmpty() && f.player.offhand.isOf(Items.TORCH),
                "Blocked toggle sent an interaction, changed input or changed offhand");
        f.player.inventory.main.set(5, ItemStack.EMPTY);
        check(action.send(f.client, f.player), "Freeing one slot did not restore moving state adjustment");
    }

    private static void putUpperHalf(StateInteractionTestFixture f, BlockState before, BlockState desired) {
        BlockPos upper = StateInteractionTestFixture.TARGET.up();
        f.world.blocks.put(upper, before.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        f.schematic.blocks.put(upper, desired.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void checkDoorHalves(StateInteractionTestFixture f) throws Exception {
        for (boolean clickUpper : new boolean[]{false, true}) {
            f.reset();
            BlockPos lower = StateInteractionTestFixture.TARGET, upper = lower.up();
            BlockState before = Blocks.OAK_DOOR.getDefaultState(), desired = before.with(DoorBlock.OPEN, true);
            f.target(before, desired); putUpperHalf(f, before, desired);
            BlockPos clicked = clickUpper ? upper : lower, partner = clickUpper ? lower : upper;
            MovingPrinterAction first = MovingPrinterAction.prepare(f.state(clicked), f.player, 4.5);
            MovingPrinterAction second = MovingPrinterAction.prepare(f.state(partner), f.player, 4.5);
            check(first != null && second != null && first.send(f.client, f.player), "Door half failed to prepare/send");
            check(Printer.hasPendingPlacement(lower) && Printer.hasPendingPlacement(upper),
                    "Door toggle did not reserve both halves");
            check(!second.send(f.client, f.player) && f.network.blockPackets.size() == 1,
                    "Other door half immediately undid a pending toggle");
            BlockState clickedDesired = f.schematic.getBlockState(clicked);
            f.world.blocks.put(clicked, clickedDesired); Printer.confirmNativePlacement(clicked, clickedDesired);
            check(!Printer.hasPendingPlacement(clicked) && Printer.hasPendingPlacement(partner),
                    "One half confirmation incorrectly released both halves");
            f.setVelocity(Vec3d.ZERO);
            check(!new CycleStateGuide(f.state(partner)).canExecute(f.player),
                    "Stopping movement allowed a legacy click while the other door half was pending");
            f.world.blocks.put(clicked, before.with(DoorBlock.HALF, clickUpper ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER));
            check(!new CycleStateGuide(f.state(clicked)).canExecute(f.player),
                    "A released door half ignored the pending partner after a stale update");
            f.configure(LitematicaMixinMod.PRINT_MODE, false);
            for (int i = 0; i < 65; i++) f.printer.onGameTick();
            check(!Printer.hasPendingPlacement(partner), "Door pending state failed to time out");
            f.configure(LitematicaMixinMod.PRINT_MODE, true);
            check(new CycleStateGuide(f.state(partner)).canExecute(f.player), "Door did not become retryable after timeout");
        }
    }

    private static void checkSchedulingPriority(StateInteractionTestFixture f) throws Exception {
        f.reset();
        BlockState trapdoor = Blocks.OAK_TRAPDOOR.getDefaultState();
        f.target(trapdoor, trapdoor.with(Properties.OPEN, true));
        BlockPos ordinary = StateInteractionTestFixture.TARGET.east();
        f.schematic.blocks.put(ordinary, Blocks.STONE.getDefaultState());
        f.world.blocks.put(ordinary.down(), Blocks.STONE.getDefaultState());
        f.player.inventory.main.set(2, new ItemStack(Items.STONE, 64));
        check(schedule(f.printer), "Ordinary placement was not scheduled");
        f.printer.flushNativePlacements();
        check(Printer.hasPendingPlacement(ordinary) && !Printer.hasPendingPlacement(StateInteractionTestFixture.TARGET),
                "Moving toggle displaced ordinary placement priority");
        f.world.blocks.put(ordinary, Blocks.STONE.getDefaultState());
        Printer.confirmNativePlacement(ordinary, Blocks.STONE.getDefaultState());
        check(schedule(f.printer), "Moving toggle did not schedule after ordinary placement completed");
        f.printer.flushNativePlacements();
        check(Printer.hasPendingPlacement(StateInteractionTestFixture.TARGET), "Moving scheduler skipped the remaining trapdoor");
    }

    private static boolean schedule(Printer printer) {
        int side = 3 + 2 * (int) Math.ceil(LitematicaMixinMod.PRINTING_RANGE.getDoubleValue());
        for (int i = 0; i < side * side * side; i++) if (printer.onGameTick()) return true;
        return false;
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}

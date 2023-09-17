package wraith.alloyforgery.block;

import com.google.common.collect.ImmutableList;
import io.wispforest.owo.ops.ItemOps;
import io.wispforest.owo.util.ImplementedInventory;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.InsertionOnlyStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.Fluids;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import wraith.alloyforgery.AlloyForgeScreenHandler;
import wraith.alloyforgery.AlloyForgery;
import wraith.alloyforgery.forges.ForgeDefinition;
import wraith.alloyforgery.forges.ForgeFuelRegistry;
import wraith.alloyforgery.forges.ForgeRegistry;
import wraith.alloyforgery.mixin.HopperBlockEntityAccessor;
import wraith.alloyforgery.recipe.AlloyForgeRecipe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public class ForgeControllerBlockEntity extends BlockEntity implements ImplementedInventory, SidedInventory, NamedScreenHandlerFactory, InsertionOnlyStorage<FluidVariant> {

    private static final int[] DOWN_SLOTS = new int[]{10, 11};
    private static final int[] RIGHT_SLOTS = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    private static final int[] LEFT_SLOTS = new int[]{11};

    public static final int INVENTORY_SIZE = 12;
    private final DefaultedList<ItemStack> items = DefaultedList.ofSize(INVENTORY_SIZE, ItemStack.EMPTY);

    private final FluidHolder fluidHolder = new FluidHolder();

    private final ForgeDefinition forgeDefinition;
    private final ImmutableList<BlockPos> multiblockPositions;
    private final Direction facing;

    private float fuel;
    private int currentSmeltTime;

    private int smeltProgress;
    private int fuelProgress;
    private int lavaProgress;

    public ForgeControllerBlockEntity(BlockPos pos, BlockState state) {
        super(AlloyForgery.FORGE_CONTROLLER_BLOCK_ENTITY, pos, state);
        forgeDefinition = ((ForgeControllerBlock) state.getBlock()).forgeDefinition;
        facing = state.get(ForgeControllerBlock.FACING);

        multiblockPositions = generateMultiblockPositions(pos.toImmutable(), state.get(ForgeControllerBlock.FACING));
    }

    private final PropertyDelegate properties = new PropertyDelegate() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> smeltProgress;
                case 1 -> fuelProgress;
                default -> lavaProgress;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int size() {
            return 3;
        }
    };

    @Override
    public void readNbt(NbtCompound nbt) {
        Inventories.readNbt(nbt, items);

        this.currentSmeltTime = nbt.getInt("CurrentSmeltTime");
        this.fuel = nbt.getInt("Fuel");

        final var fluidNbt = nbt.getCompound("FuelFluidInput");
        this.fluidHolder.amount = fluidNbt.getLong("Amount");
        this.fluidHolder.variant = FluidVariant.fromNbt(nbt.getCompound("Variant"));
    }

    @Override
    public void writeNbt(NbtCompound nbt) {
        Inventories.writeNbt(nbt, items);

        nbt.putInt("Fuel", Math.round(fuel));
        nbt.putInt("CurrentSmeltTime", currentSmeltTime);

        final var fluidNbt = new NbtCompound();
        fluidNbt.putLong("Amount", this.fluidHolder.amount);
        fluidNbt.put("Variant", this.fluidHolder.variant.toNbt());
        nbt.put("FuelFluidInput", fluidNbt);
    }

    @Override
    public DefaultedList<ItemStack> getItems() {
        return items;
    }

    public ItemStack getFuelStack() {
        return getStack(11);
    }

    public boolean canAddFuel(int fuel) {
        return this.fuel + fuel <= forgeDefinition.fuelCapacity();
    }

    public void addFuel(int fuel) {
        this.fuel += fuel;
    }

    public int getSmeltProgress() {
        return smeltProgress;
    }

    public int getCurrentSmeltTime() {
        return currentSmeltTime;
    }

    public void tick() {
        this.smeltProgress = Math.round((this.currentSmeltTime / (float) forgeDefinition.maxSmeltTime()) * 19);
        this.fuelProgress = Math.round((this.fuel / (float) forgeDefinition.fuelCapacity()) * 48);
        this.lavaProgress = Math.round((this.fluidHolder.getAmount() / (float) FluidConstants.BUCKET) * 50);

        if (this.getWorld() == null) {
            return;
        }
        var world = this.getWorld();

        world.updateComparators(pos, getCachedState().getBlock());

        final var currentBlockState = world.getBlockState(pos);

        if (!this.verifyMultiblock()) {
            this.currentSmeltTime = 0;

            if (currentBlockState.get(ForgeControllerBlock.LIT)) {
                world.setBlockState(pos, currentBlockState.with(ForgeControllerBlock.LIT, false));
            }

            return;
        }

        if (!this.getFuelStack().isEmpty()) {
            final var fuelStack = this.getFuelStack();
            final var fuelDefinition = ForgeFuelRegistry.getFuelForItem(fuelStack.getItem());

            if (fuelDefinition != ForgeFuelRegistry.ForgeFuelDefinition.EMPTY && canAddFuel(fuelDefinition.fuel())) {

                if (!ItemOps.emptyAwareDecrement(this.getFuelStack())) {
                    setStack(11, fuelDefinition.hasReturnType() ? new ItemStack(fuelDefinition.returnType()) : ItemStack.EMPTY);
                }

                this.fuel += fuelDefinition.fuel();
            }
        }

        if (this.fluidHolder.amount >= 81) {
            final float fuelInsertAmount = Math.min((this.fluidHolder.amount / 81f) * 24, ((this.forgeDefinition.fuelCapacity() - this.fuel) / 24) * 24);

            this.fuel += fuelInsertAmount;
            this.fluidHolder.amount -= (fuelInsertAmount / 24) * 81;
        }

        if (this.fuel > 100 && !currentBlockState.get(ForgeControllerBlock.LIT)) {
            world.setBlockState(pos, currentBlockState.with(ForgeControllerBlock.LIT, true));
        } else if (fuel < 100 && currentBlockState.get(ForgeControllerBlock.LIT)) {
            world.setBlockState(pos, currentBlockState.with(ForgeControllerBlock.LIT, false));
        }

        // Don't handle recipes if the forge is empty
        if (this.isEmpty()) {
            this.currentSmeltTime = 0;
            return;
        }


        // TODO - If the forge was empty, but there is no updates in its inventory, then it might constantly check for new recipes
        // TODO - Perhaps this could be optimized?
        final var recipeOptional = world.getRecipeManager().getFirstMatch(AlloyForgeRecipe.Type.INSTANCE, this, world);

        if (recipeOptional.isEmpty()) {
            this.currentSmeltTime = 0;
            return;
        }

        final var recipe = recipeOptional.get();
        if (recipe.getMinForgeTier() > this.forgeDefinition.forgeTier()) {
            this.currentSmeltTime = 0;
            return;
        }

        final var outputStack = this.getStack(10);
        final var recipeOutput = recipe.getOutput(this.forgeDefinition.forgeTier());

        if (!outputStack.isEmpty() && !ItemOps.canStack(outputStack, recipeOutput)) {
            this.currentSmeltTime = 0;
            return;
        }

        // Process the recipe, or handle remainders once it finishes
        if (this.currentSmeltTime < this.forgeDefinition.maxSmeltTime()) {

            final float fuelRequirement = recipe.getFuelPerTick() * this.forgeDefinition.speedMultiplier();
            if (this.fuel - fuelRequirement < 0) {
                this.currentSmeltTime = 0;
                return;
            }

            this.currentSmeltTime++;
            this.fuel -= fuelRequirement;

            if (world.random.nextDouble() > 0.75) {
                AlloyForgery.FORGE_PARTICLES.spawn(world, Vec3d.of(pos), facing);
            }
        } else {
            var remainderList = recipe.gatherRemainders(this);

            recipe.craft(this, world.getRegistryManager());

            if (remainderList != null) this.handleForgingRemainders(remainderList);

            if (outputStack.isEmpty()) {
                this.setStack(10, recipeOutput);
            } else {
                outputStack.increment(recipeOutput.getCount());
            }

            this.currentSmeltTime = 0;
        }
    }

    private void handleForgingRemainders(DefaultedList<ItemStack> remainderList) {
        for (int i = 0; i < remainderList.size(); ++i) {
            var inputStack = this.getStack(i);
            var remainderStack = remainderList.get(i);
            var world = this.getWorld();

            if (world == null || remainderStack.isEmpty()) {
                return;
            }

            if (inputStack.isEmpty()) {
                this.setStack(i, remainderStack);
            } else if (ItemStack.areItemsEqual(inputStack, remainderStack) && ItemStack.areEqual(inputStack, remainderStack)) {
                remainderStack.increment(inputStack.getCount());

                if (remainderStack.getCount() > remainderStack.getMaxCount()) {
                    int excess = remainderStack.getCount() - remainderStack.getMaxCount();
                    remainderStack.decrement(excess);

                    var insertStack = remainderStack.copy();
                    insertStack.setCount(excess);

                    if (!this.attemptToInsertIntoHopper(insertStack)) {
                        var frontForgePos = pos.offset(getCachedState().get(ForgeControllerBlock.FACING));

                        world.playSound(null, frontForgePos.getX(), frontForgePos.getY(), frontForgePos.getZ(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 1.0F, 0.2F);
                        ItemScatterer.spawn(world, frontForgePos.getX(), frontForgePos.getY(), frontForgePos.getZ(), insertStack);
                    }
                }

                this.setStack(i, remainderStack);
            } else {
                if (!this.attemptToInsertIntoHopper(remainderStack)) {
                    var frontForgePos = pos.offset(getCachedState().get(ForgeControllerBlock.FACING));

                    world.playSound(null, frontForgePos.getX(), frontForgePos.getY(), frontForgePos.getZ(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 1.0F, 0.2F);
                    ItemScatterer.spawn(world, frontForgePos.getX(), frontForgePos.getY(), frontForgePos.getZ(), remainderStack);
                }
            }
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean attemptToInsertIntoHopper(ItemStack remainderStack) {
        if (remainderStack.isEmpty()) return true;

        HopperBlockEntity blockEntity = null;

        for (int y = 1; y <= 2; y++) {
            if (this.world.getBlockEntity(this.pos.down(y)) instanceof HopperBlockEntity hopperBlockEntity) {
                blockEntity = hopperBlockEntity;

                break;
            }
        }

        if (blockEntity == null) {
            return false;
        }

        var isHopperEmpty = blockEntity.isEmpty();

        for (int slotIndex = 0; slotIndex < blockEntity.size(); ++slotIndex) {
            if (remainderStack.isEmpty()) break;

            if (!blockEntity.getStack(slotIndex).isEmpty()) {
                final var itemStack = blockEntity.getStack(slotIndex).copy();

                if (itemStack.isEmpty()) {
                    blockEntity.setStack(slotIndex, remainderStack);
                    remainderStack = ItemStack.EMPTY;
                } else if (ItemOps.canStack(itemStack, remainderStack)) {
                    int availableSpace = itemStack.getMaxCount() - itemStack.getCount();
                    int j = Math.min(itemStack.getCount(), availableSpace);
                    remainderStack.decrement(j);
                    itemStack.increment(j);
                }
            } else {
                blockEntity.setStack(slotIndex, remainderStack);
                break;
            }
        }

        if (isHopperEmpty && !((HopperBlockEntityAccessor) blockEntity).alloyForge$isDisabled()) {
            ((HopperBlockEntityAccessor) blockEntity).alloyForge$setTransferCooldown(8);
        }

        blockEntity.markDirty();

        return true;

    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean verifyMultiblock() {
        final BlockState belowController = world.getBlockState(multiblockPositions.get(0));
        if (!(belowController.isOf(Blocks.HOPPER) || forgeDefinition.isBlockValid(belowController.getBlock())))
            return false;

        for (int i = 1; i < multiblockPositions.size(); i++) {
            if (!forgeDefinition.isBlockValid(world.getBlockState(multiblockPositions.get(i)).getBlock())) return false;
        }

        return true;
    }

    private static ImmutableList<BlockPos> generateMultiblockPositions(BlockPos controllerPos, Direction controllerFacing) {
        final List<BlockPos> posses = new ArrayList<>();
        final BlockPos center = controllerPos.offset(controllerFacing.getOpposite());

        for (BlockPos pos : BlockPos.iterate(center.add(1, -1, 1), center.add(-1, -1, -1))) {
            posses.add(pos.toImmutable());
        }

        posses.remove(controllerPos.down());
        posses.add(0, controllerPos.down());

        for (int i = 0; i < 2; i++) {
            final var newCenter = center.add(0, i, 0);

            posses.add(newCenter.east());
            posses.add(newCenter.west());
            posses.add(newCenter.north());
            posses.add(newCenter.south());
        }

        posses.remove(controllerPos);
        return ImmutableList.copyOf(posses);
    }

    @Override
    public int[] getAvailableSlots(Direction side) {
        if (side == Direction.DOWN) {
            return DOWN_SLOTS;
        } else if (side == facing.rotateYClockwise()) {
            return LEFT_SLOTS;
        } else if (side == facing.rotateYCounterclockwise() && this.currentSmeltTime == 0) {
            return RIGHT_SLOTS;
        } else {
            return new int[0];
        }
    }

    @Override
    public boolean canInsert(int slot, ItemStack stack, @Nullable Direction dir) {
        return slot == 11 ? ForgeFuelRegistry.hasFuel(stack.getItem()) : getStack(slot).isEmpty();
    }

    @Override
    public boolean canExtract(int slot, ItemStack stack, Direction dir) {
        return slot == 10 || (slot == 11 && !ForgeFuelRegistry.hasFuel(stack.getItem()));
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable("container.alloy_forgery.forge_controller");
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity player) {
        return new AlloyForgeScreenHandler(syncId, inv, this, properties);
    }

    @Override
    public long insert(FluidVariant resource, long maxAmount, TransactionContext transaction) {
        return this.fluidHolder.insert(resource, maxAmount, transaction);
    }

    @Override
    public Iterator<StorageView<FluidVariant>> iterator() {
        return this.fluidHolder.iterator();
    }

    private class FluidHolder extends SingleVariantStorage<FluidVariant> implements InsertionOnlyStorage<FluidVariant> {
        @Override
        protected FluidVariant getBlankVariant() {
            return FluidVariant.blank();
        }

        @Override
        protected long getCapacity(FluidVariant variant) {
            return FluidConstants.BUCKET;
        }

        @Override
        protected void onFinalCommit() {
            ForgeControllerBlockEntity.this.markDirty();
        }

        @Override
        protected boolean canInsert(FluidVariant variant) {
            return variant.isOf(Fluids.LAVA);
        }

        @Override
        protected boolean canExtract(FluidVariant variant) {
            return false;
        }

        @Override
        public Iterator<StorageView<FluidVariant>> iterator() {
            return InsertionOnlyStorage.super.iterator();
        }
    }

    public static class Type extends BlockEntityType<ForgeControllerBlockEntity> {

        public static Type INSTANCE = new Type();

        private Type() {
            super(ForgeControllerBlockEntity::new, ForgeRegistry.controllerBlocksView(), null);
        }
    }
}

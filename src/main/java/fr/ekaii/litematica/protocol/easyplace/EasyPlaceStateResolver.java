package fr.ekaii.litematica.protocol.easyplace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side decoder of the Litematica <strong>Easy Place V3</strong>
 * protocol value into a concrete {@link BlockState}, on Paper 26.2 NMS.
 *
 * <h2>Wire contract (interoperability facts, not copied code)</h2>
 * The client packs, into the X-fractional of the cursor position of a
 * vanilla {@code ServerboundUseItemOnPacket}, the bit field
 * {@code protocolValue} such that {@code cursor.x = relX + 2 + protocolValue}
 * (see {@link EasyPlaceProtocolDecoder}). The bit layout, lowest bit first:
 * <ol>
 *   <li>bit 0: reserved (always 0)</li>
 *   <li>bits 1..3: {@code Direction#get3DDataValue()} of the block's
 *       <em>first</em> direction property (the first {@code EnumProperty}
 *       whose value class is {@code Direction} in the state's property
 *       iteration order), unless that property is
 *       {@code vertical_direction}; value 6 means "opposite of the state's
 *       current facing". Present only when such a property exists.</li>
 *   <li>then, for every property of the block sorted by name, that is in
 *       the shared whitelist ({@link #WHITELIST}) and not in the reset
 *       list, and is not the direction property consumed above: the index
 *       of the schematic value in the property's naturally sorted value
 *       list, using {@code log2(smallestEncompassingPowerOfTwo(count))}
 *       bits.</li>
 * </ol>
 * Servux 26.2 / Litematica 26.2-0.28.x are the reference implementations;
 * the whitelist and the reset list must stay identical to the client's or
 * the bit cursor desynchronises.
 *
 * <h2>Decode algorithm</h2>
 * Mirrors Servux's {@code PlacementHandler.applyPlacementProtocolV3}
 * semantics: apply the direction (with the "not a possible value, use the
 * player's opposite facing" fallback), shift by 3, shift by 1, then walk
 * the sorted properties consuming the per-property bit width; a decoded
 * {@code SlabType.DOUBLE} is never applied (slab duplication); afterwards
 * {@code waterlogged} and {@code powered} are reset to {@code false}, and
 * {@code waterlogged} is re-enabled when the vanilla placement was
 * waterlogged or placed into still water. Every step is validated with
 * {@link BlockState#canSurvive} against the target position and rolled
 * back to the last surviving state when it fails, like Servux's optional
 * validator.
 *
 * <h2>Differences vs Servux (documented, intentional)</h2>
 * <ul>
 *   <li>Servux mutates the state <em>before</em> the block is placed (mixin
 *       in {@code BlockItem#getPlacementState}); this plugin reads the
 *       vanilla-placed state inside {@code BlockPlaceEvent} and computes the
 *       corrected state from it. Placement-time side effects that depend
 *       on the final state (double-chest merging, bed head position) are
 *       therefore vanilla's, not the schematic's.</li>
 *   <li>Beds are left untouched (returns {@code null}): re-orienting a
 *       placed bed would require relocating the head half.</li>
 *   <li>Servux returns {@code null} (placement fails) when validation
 *       fails for the final state; here the vanilla placement stands.</li>
 * </ul>
 *
 * <p>All methods must run on the thread owning the target chunk (region
 * thread on Folia, main thread on Paper).
 */
public final class EasyPlaceStateResolver {

    /**
     * Properties the protocol may set, byte-identical to Servux 26.2
     * {@code PlacementHandler.WHITELISTED_PROPERTIES} (which Litematica
     * 26.2-0.28.x mirrors on the client). Order is irrelevant: the wire
     * order is the block's properties sorted by name.
     */
    static final Set<Property<?>> WHITELIST = Set.of(
            BlockStateProperties.INVERTED,
            BlockStateProperties.OPEN,
            BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.AXIS,
            BlockStateProperties.HALF,
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.CHEST_TYPE,
            BlockStateProperties.MODE_COMPARATOR,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.ORIENTATION,
            BlockStateProperties.RAIL_SHAPE,
            BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.STAIRS_SHAPE,
            BlockStateProperties.COPPER_GOLEM_POSE,
            BlockStateProperties.BITES,
            BlockStateProperties.DELAY,
            BlockStateProperties.NOTE,
            BlockStateProperties.ROTATION_16
    );

    /**
     * Properties the protocol never transmits and that are forced to a
     * default after decoding (Servux {@code BLACKLISTED_PROPERTIES}).
     */
    static final Map<Property<?>, Comparable<?>> RESET_TO = Map.of(
            BlockStateProperties.WATERLOGGED, Boolean.FALSE,
            BlockStateProperties.POWERED, Boolean.FALSE
    );

    private EasyPlaceStateResolver() {
    }

    /**
     * Resolves the schematic-intended state from the vanilla-placed one.
     *
     * @param placed        the state vanilla placement produced at {@code pos}
     * @param protocolValue the decoded (non-negative) protocol value
     * @param level         the level, for survival validation
     * @param pos           the position of the placed block
     * @param playerFacing  the placing player's facing, for the Servux
     *                      "invalid facing" fallback; may be {@code null}
     * @return the corrected state, or {@code null} when nothing should change
     */
    public static BlockState resolve(BlockState placed, int protocolValue, ServerLevel level,
                                     BlockPos pos, Direction playerFacing) {
        if (placed == null || protocolValue < 0) {
            return null;
        }
        if (placed.getBlock() instanceof BedBlock) {
            // Head half already placed by vanilla at the player-facing side.
            return null;
        }

        BlockState state = placed;
        BlockState accepted = placed;
        int pv = protocolValue;

        EnumProperty<Direction> directionProperty = firstDirectionProperty(state);
        if (directionProperty != null && directionProperty != BlockStateProperties.VERTICAL_DIRECTION) {
            state = applyDirection(state, directionProperty, pv, playerFacing);
            accepted = survives(state, level, pos) ? state : accepted;
            state = accepted;
            pv >>>= 3;
        }
        pv >>>= 1;

        List<Property<?>> properties = new ArrayList<>(state.getBlock().getStateDefinition().getProperties());
        properties.sort(Comparator.comparing(Property::getName));
        try {
            for (Property<?> property : properties) {
                if (property == directionProperty) {
                    continue;
                }
                if (!WHITELIST.contains(property) || RESET_TO.containsKey(property)) {
                    continue;
                }
                Step step = applyIndexed(state, property, pv);
                if (step.state != state) {
                    accepted = survives(step.state, level, pos) ? step.state : accepted;
                    state = accepted;
                }
                pv >>>= step.consumedBits;
            }
        } catch (RuntimeException e) {
            // Mirrors upstream: a malformed bit field never breaks placement.
            state = accepted;
        }

        state = resetBlacklisted(state);
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)
                && ((placed.hasProperty(BlockStateProperties.WATERLOGGED)
                        && placed.getValue(BlockStateProperties.WATERLOGGED))
                    || placed.getFluidState().getType().isSame(Fluids.WATER))) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, true);
        }

        if (state == placed) {
            return null;
        }
        if (!survives(state, level, pos)) {
            return null;
        }
        return state;
    }

    /**
     * Copies every property value of {@code source} that {@code target}
     * also declares, except {@code double_block_half}. Used to keep the
     * second half of two-block-tall blocks (doors, small dripleaf, ...)
     * consistent with the corrected primary half.
     */
    public static BlockState copyForOtherHalf(BlockState source, BlockState target) {
        BlockState result = target;
        for (Property<?> property : source.getProperties()) {
            if (property == BlockStateProperties.DOUBLE_BLOCK_HALF) {
                continue;
            }
            if (target.hasProperty(property)) {
                result = copyValue(source, result, property);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- steps

    private record Step(BlockState state, int consumedBits) {
    }

    private static <T extends Comparable<T>> Step applyIndexed(BlockState state, Property<T> property, int pv) {
        List<T> values = new ArrayList<>(property.getPossibleValues());
        values.sort(Comparator.naturalOrder());
        int requiredBits = Mth.log2(Mth.smallestEncompassingPowerOfTwo(values.size()));
        int mask = ~(0xFFFFFFFF << requiredBits);
        int index = pv & mask;
        if (index < 0 || index >= values.size()) {
            // Upstream leaves the bit cursor where it is in this case.
            return new Step(state, 0);
        }
        T value = values.get(index);
        BlockState next = state;
        if (!state.getValue(property).equals(value) && value != SlabType.DOUBLE) {
            next = state.setValue(property, value);
        }
        return new Step(next, requiredBits);
    }

    private static BlockState applyDirection(BlockState state, EnumProperty<Direction> property,
                                             int pv, Direction playerFacing) {
        Direction original = state.getValue(property);
        Direction facing = original;
        int index = EasyPlaceProtocolDecoder.decodeFacingIndex(pv);
        if (index == 6) {
            facing = original.getOpposite();
        } else if (index >= 0 && index <= 5) {
            facing = Direction.from3DDataValue(index);
            if (!property.getPossibleValues().contains(facing)) {
                facing = playerFacing != null ? playerFacing.getOpposite() : original;
            }
        }
        if (facing != original && property.getPossibleValues().contains(facing)) {
            return state.setValue(property, facing);
        }
        return state;
    }

    private static BlockState resetBlacklisted(BlockState state) {
        BlockState result = state;
        for (Map.Entry<Property<?>, Comparable<?>> entry : RESET_TO.entrySet()) {
            if (result.hasProperty(entry.getKey())) {
                result = setRaw(result, entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState setRaw(BlockState state, Property<T> property,
                                                                Comparable<?> value) {
        return state.setValue(property, (T) value);
    }

    private static <T extends Comparable<T>> BlockState copyValue(BlockState source, BlockState target,
                                                                   Property<T> property) {
        return target.setValue(property, source.getValue(property));
    }

    @SuppressWarnings("unchecked")
    static EnumProperty<Direction> firstDirectionProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof EnumProperty<?> enumProperty
                    && enumProperty.getValueClass().equals(Direction.class)) {
                return (EnumProperty<Direction>) enumProperty;
            }
        }
        return null;
    }

    private static boolean survives(BlockState state, ServerLevel level, BlockPos pos) {
        try {
            return state.canSurvive(level, pos);
        } catch (RuntimeException e) {
            return false;
        }
    }
}

package ram.talia.hexal.api.casting.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.NullIota;
import at.petrak.hexcasting.api.utils.HexUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ram.talia.hexal.api.mediafieditems.ItemRecord;
import ram.talia.hexal.api.mediafieditems.MediafiedItemManager;
import ram.talia.hexal.common.lib.hex.HexalIotaTypes;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Similar to GateIotas, stores a reference to an item stored in the
 * media. When the item is used up, all references to it become null.
 */
public class MoteIota extends Iota {
    static final String TAG_ITEM_RECORD = "item_record";

    /**
     * Used to get the UUID of the temporarily bound storage from userData, if one exists.
     */
    public static final String TAG_TEMP_STORAGE = "hexal:temp_storage";

    public MoteIota(MediafiedItemManager.Index payload) {
        super(HexalIotaTypes.MOTE, payload);
    }

    public static @Nullable MoteIota makeIfStorageLoaded(ItemStack stack, UUID storageUUID) {
        var index = MediafiedItemManager.assignItem(stack, storageUUID);

        if (index != null)
            return new MoteIota(index);
        else
            return null;
    }

    public static @Nullable MoteIota makeIfStorageLoaded(ItemRecord record, UUID storageUUID) {
        var index = MediafiedItemManager.assignItem(record, storageUUID);

        if (index != null)
            return new MoteIota(index);
        else
            return null;
    }

    /**
     * Returns the MoteIota if its item still exists, or null otherwise. SHOULD ALWAYS
     * BE CALLED BEFORE MAKING USE OF AN ITEM IOTA {@literal (built into List<Iota>.getItem)}.
     */
    public @Nullable MoteIota selfOrNull() {
        if (MediafiedItemManager.contains(this.getItemIndex()))
            return this;
        return null;
    }

    public boolean isEmpty() {
        return MediafiedItemManager.isEmpty(this.getItemIndex());
    }

    public MediafiedItemManager.Index getItemIndex() {
        return (MediafiedItemManager.Index) payload;
    }

    public @Nullable ItemRecord getRecord() {
        var record = MediafiedItemManager.getRecord(this.getItemIndex());
        if (record == null)
            return null;
        return record.get();
    }

    public Item getItem() {
        return Objects.requireNonNull(MediafiedItemManager.getItem(this.getItemIndex()), "MediafiedItemManager returned null for Item that has existing MoteIota.");
    }

    public CompoundTag getTag() {
        return MediafiedItemManager.getTag(this.getItemIndex());
    }

    public void setTag(CompoundTag tag) {
        MediafiedItemManager.setTag(this.getItemIndex(), tag);
    }

    public long getCount() {
        return Objects.requireNonNull(MediafiedItemManager.getCount(this.getItemIndex()), "MediafiedItemManager returned null for Item that has existing MoteIota.");
    }

    public void absorb(MoteIota other) {
        MediafiedItemManager.merge(this.getItemIndex(), other.getItemIndex());
    }

    public int absorb(ItemStack other) {
        return MediafiedItemManager.merge(this.getItemIndex(), other);
    }

    public boolean typeMatches(MoteIota other) {
        return MediafiedItemManager.typeMatches(this.getItemIndex(), other.getItemIndex());
    }

    public boolean typeMatches(ItemStack other) {
        return MediafiedItemManager.typeMatches(this.getItemIndex(), other);
    }

    public @Nullable MoteIota splitOff(long amount, @Nullable UUID storage) {
        var newIndex = MediafiedItemManager.splitOff(this.getItemIndex(), amount, storage);
        if (newIndex == null)
            return null;

        return new MoteIota(newIndex);
    }

    public List<ItemStack> getStacksToDrop(int count) {
        return MediafiedItemManager.getStacksToDrop(this.getItemIndex(), count);
    }

    public long removeItems(int count) {
        return removeItems((long) count);
    }

    public long removeItems(long count) {
        return MediafiedItemManager.removeItems(this.getItemIndex(), count);
    }

    /**
     * Takes a template ItemStack and sets the item and tag of the referenced ItemRecord to that item and tag, while leaving the count the same.
     */
    public void templateOff(@NotNull ItemStack template) {
        MediafiedItemManager.templateOff(this.getItemIndex(), template, null);
    }

    /**
     * Takes a template ItemStack and sets the item and tag of the referenced ItemRecord to that item and tag, as well as overriding the count to newCount.
     */
    public void templateOff(@NotNull ItemStack template, long newCount) {
        MediafiedItemManager.templateOff(this.getItemIndex(), template, newCount);
    }

    public MoteIota copy() {
        return new MoteIota(this.getItemIndex());
    }

    public @Nullable MoteIota setStorage(@NotNull UUID uuid) {
        var storageFull = MediafiedItemManager.isStorageFull(uuid);
        if (storageFull == null || storageFull) // isStorageFull can return null
            return null;

        var record = getRecord();
        MediafiedItemManager.removeRecord(this.getItemIndex());
        if (record == null)
            return null;

        var newIndex = MediafiedItemManager.assignItem(record, uuid);
        return new MoteIota(newIndex);
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        return (typesMatch(this, that) &&
                that instanceof MoteIota ithat &&
                this.getItemIndex().equals(ithat.getItemIndex())) ||
                (this.isEmpty() && (that instanceof NullIota ||
                        (that instanceof MoteIota ithat2 &&
                                ithat2.isEmpty())));
    }

    @Override
    public boolean isTruthy() {
        return !this.isEmpty();
    }

    @Override
    public @NotNull Tag serialize() {
        // needs to contain both the index and the current contents
        // of the referenced item, since the same serialised is
        // used for both storage and for sending to the client
        // to display.

        var tag = new CompoundTag();
        this.getItemIndex().writeToNbt(tag);

        var record = MediafiedItemManager.getRecord(this.getItemIndex());

        ItemRecord rec;

        if (record == null || (rec = record.get()) == null)
            return tag;

        rec.writeToTag(tag);

        return tag;
    }

    public static IotaType<MoteIota> TYPE = new IotaType<>() {
        @Override
        public MoteIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);

            var index = MediafiedItemManager.Index.readFromNbt(ctag);

            if (!MediafiedItemManager.contains(index))
                return null;

            return new MoteIota(index);
        }

        @Override
        public Component display(Tag tag) {
            if (!(tag instanceof CompoundTag ctag)) {
                return Component.translatable("hexcasting.spelldata.unknown");
            }

            var itemRecord = ItemRecord.readFromTag(ctag);
            if (itemRecord == null)
                return Component.translatable("hexcasting.tooltip.null_iota").withStyle(ChatFormatting.GRAY);

            return Component.translatable("hexal.spelldata.mote", itemRecord.getDisplayName(), itemRecord.getCount()).withStyle(ChatFormatting.YELLOW);
        }

        @Override
        public int color() {
            return 0xff_ffff55;
        }
    };
}

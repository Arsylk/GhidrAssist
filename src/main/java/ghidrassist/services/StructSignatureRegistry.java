package ghidrassist.services;

import ghidra.program.model.data.BitFieldDataType;
import ghidra.program.model.data.Composite;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeComponent;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.Structure;
import ghidra.util.Msg;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Exact-duplicate struct dedup registry used by global auto-analyze.
 *
 * Canonical signature = (totalLength, orderedList[(ordinal, offset, componentLength,
 * dataTypePathName, bitFieldMeta, isFlexible)]). Field names are NOT part of the
 * signature (they may be improved between passes) but are used as a safety discriminator
 * when a name collision occurs with a different layout.
 *
 * Semantics (per oracle review, strict):
 *   - Same signature as an already-registered struct -> REUSE the registered struct.
 *   - Different signature but same name -> generate a UNIQUE name; never blindly KEEP_HANDLER.
 *   - DataTypeConflictHandler.KEEP_HANDLER is intentionally NOT used anywhere, because it
 *     silently reuses a wrong-layout struct on name collision. We always use
 *     DEFAULT_HANDLER with a pre-validated unique name.
 *
 * Single-threaded usage only. Build once at start of a batch via buildFromDtm().
 */
public class StructSignatureRegistry {

    private final DataTypeManager dtm;
    private final Map<String, Structure> bySignature = new HashMap<>();
    private final Map<String, Structure> byName = new HashMap<>();

    public StructSignatureRegistry(DataTypeManager dtm) {
        this.dtm = dtm;
    }

    public void buildFromDtm() {
        bySignature.clear();
        byName.clear();
        if (dtm == null) {
            return;
        }
        Iterator<Structure> it = dtm.getAllStructures();
        while (it.hasNext()) {
            Structure s = it.next();
            if (s == null || s.isNotYetDefined()) {
                continue;
            }
            try {
                String sig = canonicalSignature(s);
                bySignature.putIfAbsent(sig, s);
                byName.putIfAbsent(s.getName(), s);
            } catch (Exception e) {
                Msg.debug(this, "Skipping struct during registry build: " + s.getName() + " -> " + e.getMessage());
            }
        }
    }

    /**
     * Register a candidate struct.
     *   - If an existing struct has the same canonical signature, return it (REUSE).
     *   - Else add to dtm under a unique name and return the stored copy.
     *
     * Never returns null unless dtm.addDataType itself fails.
     */
    public Structure registerOrReuse(Structure candidate) {
        if (candidate == null || dtm == null) {
            return null;
        }
        String sig = canonicalSignature(candidate);
        Structure existing = bySignature.get(sig);
        if (existing != null) {
            return existing;
        }

        String desiredName = candidate.getName();
        String safeName = desiredName;
        if (byName.containsKey(desiredName)) {
            safeName = uniqueName(desiredName);
            try {
                candidate.setName(safeName);
            } catch (Exception e) {
                Msg.warn(this, "Failed to rename struct candidate from '" + desiredName
                    + "' to '" + safeName + "': " + e.getMessage());
            }
        }

        Structure stored = (Structure) dtm.addDataType(candidate, DataTypeConflictHandler.DEFAULT_HANDLER);
        if (stored != null) {
            bySignature.put(canonicalSignature(stored), stored);
            byName.put(stored.getName(), stored);
        }
        return stored;
    }

    private String uniqueName(String base) {
        for (int i = 1; i < 10_000; i++) {
            String candidate = base + "_v" + i;
            if (!byName.containsKey(candidate)) {
                return candidate;
            }
        }
        return base + "_" + System.nanoTime();
    }

    /**
     * Deterministic, layout-only signature.
     * Field names intentionally excluded. Total length + each DEFINED component's
     * (ordinal, offset, length, datatype path name, bitfield signature, flex flag) included.
     */
    private static String canonicalSignature(Structure s) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("LEN=").append(s.isZeroLength() ? 0 : s.getLength());
        sb.append(";PACK=").append(s.getPackingType()).append('/').append(safePackingValue(s));
        sb.append(";ALIGN=").append(s.getAlignmentType()).append('/').append(s.getAlignment());
        sb.append(";FLEX=").append(hasFlexibleArray(s));
        sb.append(";COMPS=[");
        DataTypeComponent[] defined = s.getDefinedComponents();
        for (int i = 0; i < defined.length; i++) {
            DataTypeComponent c = defined[i];
            if (i > 0) sb.append(',');
            sb.append('{')
              .append("ord=").append(c.getOrdinal())
              .append(",off=").append(c.getOffset())
              .append(",len=").append(c.getLength())
              .append(",dt=").append(componentTypeSignature(c.getDataType()))
              .append(",bf=").append(bitFieldSignature(c))
              .append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static int safePackingValue(Composite c) {
        try {
            if (c.hasExplicitPackingValue()) {
                return c.getExplicitPackingValue();
            }
        } catch (Exception ignore) {
            // older ghidra versions may throw; fall through
        }
        return -1;
    }

    private static boolean hasFlexibleArray(Structure s) {
        DataTypeComponent[] defined = s.getDefinedComponents();
        if (defined.length == 0) return false;
        DataTypeComponent last = defined[defined.length - 1];
        return last != null && !last.isZeroBitFieldComponent()
            && last.getLength() == 0;
    }

    private static String componentTypeSignature(DataType dt) {
        if (dt == null) return "null";
        if (dt instanceof BitFieldDataType) {
            BitFieldDataType bf = (BitFieldDataType) dt;
            DataType base = bf.getBaseDataType();
            return "BF(" + (base != null ? base.getPathName() : "null")
                + ":" + bf.getBitSize() + "@" + bf.getBitOffset() + ")";
        }
        return dt.getPathName();
    }

    private static String bitFieldSignature(DataTypeComponent c) {
        if (!c.isBitFieldComponent()) return "-";
        DataType dt = c.getDataType();
        if (dt instanceof BitFieldDataType) {
            BitFieldDataType bf = (BitFieldDataType) dt;
            return "bs=" + bf.getBitSize() + ",bo=" + bf.getBitOffset()
                + ",zl=" + bf.isZeroLength();
        }
        return "?";
    }
}

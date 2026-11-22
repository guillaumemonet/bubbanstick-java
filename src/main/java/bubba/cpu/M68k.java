package bubba.cpu;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal Motorola 68000 interpreter (user mode, integer instructions) used to run the native
 * routines embedded in the object scripts of set*.bin, unchanged.
 * <p>
 * Supported: all 68000 addressing modes; MOVE/MOVEA/MOVEQ/MOVEM/LEA/PEA/EXG/SWAP/EXT/CLR/TST/TAS,
 * ADD/ADDA/ADDI/ADDQ/ADDX, SUB/SUBA/SUBI/SUBQ/SUBX, CMP/CMPA/CMPI/CMPM, AND/OR/EOR (+immediate,
 * CCR forms), NEG/NEGX/NOT, MULU/MULS/DIVU/DIVS, shifts and rotates (register and memory),
 * BTST/BCHG/BCLR/BSET, ABCD/SBCD/NBCD, Bcc/BRA/BSR/DBcc/Scc, JMP/JSR/RTS/RTR/RTE, LINK/UNLK, NOP,
 * MOVE to/from CCR/SR. Unsupported opcodes throw {@link IllegalStateException}.
 * Game routines can be replaced by Java code with {@link #hook}.
 */
public final class M68k {

    /** Java replacement for a routine of the game, executed instead of the code at its address. */
    public interface Hook {
        void run(M68k cpu);
    }

    private static final int RETURN_SENTINEL = 0x00fffff0;

    public final GameMemory mem;
    public final int[] d = new int[8];
    public final int[] a = new int[8];
    public int pc;
    public boolean x, n, z, v, c;
    private final Map<Integer, Hook> hooks = new HashMap<>();
    private int opAddr;

    public M68k(GameMemory mem) {
        this.mem = mem;
    }

    /** Debug: called with the address of every instruction executed (null = off). */
    public java.util.function.IntConsumer trace;

    public void hook(int address, Hook h) {
        hooks.put(address, h);
    }

    private static final int NESTED_SENTINEL = 0x00ffffe0;

    /**
     * Call the subroutine at {@code addr} from inside a hook, on the current stack and registers
     * (used to run the original code of a ported routine for comparison). With
     * {@code bypassEntryHook}, the hook at {@code addr} itself is ignored (the original runs).
     */
    public void callNested(int addr, int maxSteps, boolean bypassEntryHook) {
        push32(NESTED_SENTINEL);
        pc = addr;
        int steps = 0;
        boolean first = true;
        while (pc != NESTED_SENTINEL) {
            Hook h = first && bypassEntryHook ? null : hooks.get(pc);
            first = false;
            if (h != null) {
                h.run(this);
                pc = pop32();
                continue;
            }
            if (++steps > maxSteps) {
                throw new IllegalStateException(String.format("68k: too many steps from $%05X (pc=$%05X)", addr, pc));
            }
            step();
        }
    }

    /** Registers and condition codes, for comparisons. */
    public int[] saveRegisters() {
        int[] r = new int[17];
        System.arraycopy(d, 0, r, 0, 8);
        System.arraycopy(a, 0, r, 8, 8);
        r[16] = ccr();
        return r;
    }

    public void restoreRegisters(int[] r) {
        System.arraycopy(r, 0, d, 0, 8);
        System.arraycopy(r, 8, a, 0, 8);
        setCcr(r[16]);
    }

    /** Call the subroutine at {@code addr} (JSR) and run until it returns. */
    public void call(int addr, int maxSteps) {
        a[7] = GameMemory.STACK_TOP;
        push32(RETURN_SENTINEL);
        pc = addr;
        int steps = 0;
        while (pc != RETURN_SENTINEL) {
            Hook h = hooks.get(pc);
            if (h != null) {
                h.run(this);
                pc = pop32(); // the hook replaces the whole routine: return from it
                continue;
            }
            if (++steps > maxSteps) {
                throw new IllegalStateException(String.format("68k: too many steps from $%05X (pc=$%05X)", addr, pc));
            }
            if (trace != null) {
                trace.accept(pc);
            }
            step();
        }
    }

    /**
     * Run from {@code start} with the current registers and stack until the program counter
     * reaches {@code stop} again (e.g. one turn of the game's main loop). Hooks apply as in
     * {@link #call}.
     */
    public void runUntil(int start, int stop, int maxSteps) {
        runUntil(start, new int[]{stop}, maxSteps);
    }

    /**
     * Run a code fragment from {@code start} until the program counter reaches one of
     * {@code stops} (returned) or the fragment returns with RTS above stack level {@code sp}
     * (returns -1; the return address is left just above the stack pointer).
     */
    public int runFragment(int start, int[] stops, int sp, int maxSteps) {
        pc = start;
        int steps = 0;
        while (true) {
            Hook h = hooks.get(pc);
            if (h != null) {
                h.run(this);
                pc = pop32();
            } else {
                if (++steps > maxSteps) {
                    throw new IllegalStateException(String.format("68k: too many steps from $%05X (pc=$%05X)", start, pc));
                }
                step();
            }
            if (Integer.compareUnsigned(a[7], sp) > 0) {
                return -1;
            }
            for (int s : stops) {
                if (pc == s) {
                    return s;
                }
            }
        }
    }

    /** Same with several stop addresses; returns the one reached. */
    public int runUntil(int start, int[] stops, int maxSteps) {
        pc = start;
        int steps = 0;
        while (true) {
            Hook h = hooks.get(pc);
            if (h != null) {
                h.run(this);
                pc = pop32();
            } else {
                if (++steps > maxSteps) {
                    throw new IllegalStateException(String.format("68k: too many steps from $%05X (pc=$%05X)", start, pc));
                }
                if (trace != null) {
                    trace.accept(pc);
                }
                step();
            }
            for (int s : stops) {
                if (pc == s) {
                    return s;
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int mask(int size) {
        return size == 1 ? 0xff : size == 2 ? 0xffff : 0xffffffff;
    }

    private static int msb(int size) {
        return size == 1 ? 0x80 : size == 2 ? 0x8000 : 0x80000000;
    }

    private static int sext(int v, int size) {
        return size == 1 ? (byte) v : size == 2 ? (short) v : v;
    }

    private int fetch16() {
        int w = mem.read16(pc);
        pc += 2;
        return w;
    }

    private int fetch32() {
        int l = mem.read32(pc);
        pc += 4;
        return l;
    }

    private void push32(int val) {
        a[7] -= 4;
        mem.write32(a[7], val);
    }

    private int pop32() {
        int val = mem.read32(a[7]);
        a[7] += 4;
        return val;
    }

    private int read(int addr, int size) {
        return size == 1 ? mem.read8(addr) : size == 2 ? mem.read16(addr) : mem.read32(addr);
    }

    private void write(int addr, int size, int val) {
        if (size == 1) {
            mem.write8(addr, val);
        } else if (size == 2) {
            mem.write16(addr, val);
        } else {
            mem.write32(addr, val);
        }
    }

    private void setNZ(int val, int size) {
        val &= mask(size);
        n = (val & msb(size)) != 0;
        z = val == 0;
    }

    private void logicFlags(int val, int size) {
        setNZ(val, size);
        v = false;
        c = false;
    }

    public int ccr() {
        return (x ? 16 : 0) | (n ? 8 : 0) | (z ? 4 : 0) | (v ? 2 : 0) | (c ? 1 : 0);
    }

    public void setCcr(int f) {
        x = (f & 16) != 0;
        n = (f & 8) != 0;
        z = (f & 4) != 0;
        v = (f & 2) != 0;
        c = (f & 1) != 0;
    }

    private boolean cond(int cc) {
        return switch (cc) {
            case 0 -> true;
            case 1 -> false;
            case 2 -> !c && !z;
            case 3 -> c || z;
            case 4 -> !c;
            case 5 -> c;
            case 6 -> !z;
            case 7 -> z;
            case 8 -> !v;
            case 9 -> v;
            case 10 -> !n;
            case 11 -> n;
            case 12 -> n == v;
            case 13 -> n != v;
            case 14 -> !z && n == v;
            default -> z || n != v;
        };
    }

    // ------------------------------------------------------------------ effective addresses

    /** Resolved operand: kind 0 = Dn, 1 = An, 2 = memory, 3 = immediate. */
    private int eaKind, eaReg, eaAddr, eaImm;

    private void resolve(int mode, int reg, int size) {
        switch (mode) {
            case 0 -> {
                eaKind = 0;
                eaReg = reg;
            }
            case 1 -> {
                eaKind = 1;
                eaReg = reg;
            }
            case 2 -> {
                eaKind = 2;
                eaAddr = a[reg];
            }
            case 3 -> {
                eaKind = 2;
                eaAddr = a[reg];
                a[reg] += (size == 1 && reg == 7) ? 2 : size;
            }
            case 4 -> {
                eaKind = 2;
                a[reg] -= (size == 1 && reg == 7) ? 2 : size;
                eaAddr = a[reg];
            }
            case 5 -> {
                eaKind = 2;
                eaAddr = a[reg] + (short) fetch16();
            }
            case 6 -> {
                eaKind = 2;
                eaAddr = indexed(a[reg]);
            }
            default -> {
                switch (reg) {
                    case 0 -> {
                        eaKind = 2;
                        eaAddr = (short) fetch16();
                    }
                    case 1 -> {
                        eaKind = 2;
                        eaAddr = fetch32();
                    }
                    case 2 -> {
                        eaKind = 2;
                        int base = pc;
                        eaAddr = base + (short) fetch16();
                    }
                    case 3 -> {
                        eaKind = 2;
                        eaAddr = indexed(pc);
                    }
                    case 4 -> {
                        eaKind = 3;
                        eaImm = size == 4 ? fetch32() : (size == 1 ? fetch16() & 0xff : fetch16());
                    }
                    default -> throw bad();
                }
            }
        }
    }

    private int indexed(int base) {
        int ext = fetch16();
        int r = (ext >> 12) & 7;
        int idx = (ext & 0x8000) != 0 ? a[r] : d[r];
        if ((ext & 0x0800) == 0) {
            idx = (short) idx;
        }
        return base + (byte) ext + idx;
    }

    private int readEA(int size) {
        return switch (eaKind) {
            case 0 -> d[eaReg] & mask(size);
            case 1 -> a[eaReg] & mask(size);
            case 2 -> read(eaAddr, size);
            default -> eaImm & mask(size);
        };
    }

    private void writeEA(int size, int val) {
        switch (eaKind) {
            case 0 -> d[eaReg] = size == 4 ? val : (d[eaReg] & ~mask(size)) | (val & mask(size));
            case 1 -> a[eaReg] = size == 4 ? val : sext(val, size);
            case 2 -> write(eaAddr, size, val);
            default -> throw bad();
        }
    }

    private int sizeOf(int bits) {
        return switch (bits) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            default -> throw bad();
        };
    }

    private IllegalStateException bad() {
        return new IllegalStateException(String.format("68k: unsupported opcode $%04X at $%05X",
                mem.read16(opAddr), opAddr));
    }

    // ------------------------------------------------------------------ arithmetic

    private int add(int src, int dst, int size, boolean withX) {
        int m = mask(size);
        long r = (dst & (long) m & 0xffffffffL) + (src & (long) m & 0xffffffffL) + (withX && x ? 1 : 0);
        int res = (int) r & m;
        c = (r & ~(m & 0xffffffffL)) != 0;
        x = c;
        v = (((src ^ res) & (dst ^ res)) & msb(size)) != 0;
        n = (res & msb(size)) != 0;
        if (withX) {
            if (res != 0) {
                z = false;
            }
        } else {
            z = res == 0;
        }
        return res;
    }

    private int sub(int src, int dst, int size, boolean withX, boolean setX) {
        int m = mask(size);
        long us = (src & 0xffffffffL & m) + (withX && x ? 1 : 0);
        long ud = dst & 0xffffffffL & m;
        int res = (int) (ud - us) & m;
        boolean carry = us > ud;
        v = (((src ^ dst) & (res ^ dst)) & msb(size)) != 0;
        n = (res & msb(size)) != 0;
        if (withX) {
            if (res != 0) {
                z = false;
            }
        } else {
            z = res == 0;
        }
        c = carry;
        if (setX) {
            x = carry;
        }
        return res;
    }

    // ------------------------------------------------------------------ BCD (scores)

    private int abcd(int src, int dst) {
        int tmp = (dst & 0x0f) + (src & 0x0f) + (x ? 1 : 0);
        if (tmp > 9) {
            tmp += 6;
        }
        tmp += (dst & 0xf0) + (src & 0xf0);
        c = tmp > 0x99;
        if (c) {
            tmp -= 0xa0;
        }
        x = c;
        tmp &= 0xff;
        if (tmp != 0) {
            z = false;
        }
        return tmp;
    }

    private int sbcd(int src, int dst) {
        int lo = (dst & 0x0f) - (src & 0x0f) - (x ? 1 : 0);
        int hi = (dst & 0xf0) - (src & 0xf0);
        if (lo < 0) {
            lo += 10;
            hi -= 0x10;
        }
        c = hi < 0;
        if (c) {
            hi += 0xa0;
        }
        x = c;
        int r = (hi + lo) & 0xff;
        if (r != 0) {
            z = false;
        }
        return r;
    }

    /** ABCD / SBCD Dy,Dx or -(Ay),-(Ax). */
    private void bcdOp(int op, boolean add) {
        int rx = (op >> 9) & 7, ry = op & 7;
        if ((op & 8) == 0) {
            int r = add ? abcd(d[ry] & 0xff, d[rx] & 0xff) : sbcd(d[ry] & 0xff, d[rx] & 0xff);
            d[rx] = (d[rx] & ~0xff) | r;
        } else {
            resolve(4, ry, 1);
            int src = readEA(1);
            resolve(4, rx, 1);
            int dst = readEA(1);
            writeEA(1, add ? abcd(src, dst) : sbcd(src, dst));
        }
    }

    // ------------------------------------------------------------------ execution

    public void step() {
        opAddr = pc;
        int op = fetch16();
        switch (op >>> 12) {
            case 0x0 -> group0(op);
            case 0x1, 0x2, 0x3 -> move(op);
            case 0x4 -> group4(op);
            case 0x5 -> group5(op);
            case 0x6 -> branch(op);
            case 0x7 -> {
                if ((op & 0x100) != 0) {
                    throw bad();
                }
                int val = (byte) op;
                d[(op >> 9) & 7] = val;
                logicFlags(val, 4);
            }
            case 0x8 -> groupOr(op);
            case 0x9 -> groupAddSub(op, false);
            case 0xb -> groupCmp(op);
            case 0xc -> groupAnd(op);
            case 0xd -> groupAddSub(op, true);
            case 0xe -> shift(op);
            default -> throw bad();
        }
    }

    private void group0(int op) {
        int mode = (op >> 3) & 7, reg = op & 7;
        if ((op & 0x0100) != 0 || (op & 0x0f00) == 0x0800) {
            if ((op & 0x0100) != 0 && mode == 1) {
                // MOVEP: bytes at every other address (d16,An) <-> data register
                int addr = a[reg] + (short) fetch16();
                int dr = (op >> 9) & 7, opmode = (op >> 6) & 3;
                int bytes = (opmode & 1) != 0 ? 4 : 2;
                if (opmode >= 2) {
                    for (int i = 0; i < bytes; i++) {
                        mem.write8(addr + 2 * i, d[dr] >>> (8 * (bytes - 1 - i)));
                    }
                } else {
                    int v = 0;
                    for (int i = 0; i < bytes; i++) {
                        v = (v << 8) | mem.read8(addr + 2 * i);
                    }
                    d[dr] = bytes == 4 ? v : (d[dr] & 0xffff0000) | v;
                }
                return;
            }
            int bit = (op & 0x0100) != 0 ? d[(op >> 9) & 7] : fetch16() & 0xff;
            int kind = (op >> 6) & 3; // 0 btst 1 bchg 2 bclr 3 bset
            int size = mode == 0 ? 4 : 1;
            bit &= mode == 0 ? 31 : 7;
            resolve(mode, reg, size);
            int val = readEA(size);
            z = (val & (1 << bit)) == 0;
            if (kind == 1) {
                writeEA(size, val ^ (1 << bit));
            } else if (kind == 2) {
                writeEA(size, val & ~(1 << bit));
            } else if (kind == 3) {
                writeEA(size, val | (1 << bit));
            }
            return;
        }
        int kind = (op >> 9) & 7;
        if ((op & 0xff) == 0x3c) { // to CCR
            int imm = fetch16() & 0x1f;
            switch (kind) {
                case 0 -> setCcr(ccr() | imm);
                case 1 -> setCcr(ccr() & imm);
                case 5 -> setCcr(ccr() ^ imm);
                default -> throw bad();
            }
            return;
        }
        if ((op & 0xff) == 0x7c) { // to SR: only the CCR part matters here
            int imm = fetch16() & 0x1f;
            switch (kind) {
                case 0 -> setCcr(ccr() | imm);
                case 1 -> setCcr(ccr() & imm);
                case 5 -> setCcr(ccr() ^ imm);
                default -> throw bad();
            }
            return;
        }
        int size = sizeOf((op >> 6) & 3);
        int imm = size == 4 ? fetch32() : (size == 1 ? fetch16() & 0xff : fetch16());
        resolve(mode, reg, size);
        int dst = readEA(size);
        switch (kind) {
            case 0 -> {
                int r = dst | imm;
                logicFlags(r, size);
                writeEA(size, r);
            }
            case 1 -> {
                int r = dst & imm;
                logicFlags(r, size);
                writeEA(size, r);
            }
            case 2 -> writeEA(size, sub(imm, dst, size, false, true));
            case 3 -> writeEA(size, add(imm, dst, size, false));
            case 5 -> {
                int r = dst ^ imm;
                logicFlags(r, size);
                writeEA(size, r);
            }
            case 6 -> sub(imm, dst, size, false, false);
            default -> throw bad();
        }
    }

    private void move(int op) {
        int size = switch (op >>> 12) {
            case 1 -> 1;
            case 3 -> 2;
            default -> 4;
        };
        resolve((op >> 3) & 7, op & 7, size);
        int val = readEA(size);
        int dmode = (op >> 6) & 7, dreg = (op >> 9) & 7;
        if (dmode == 1) { // MOVEA
            a[dreg] = sext(val, size);
            return;
        }
        resolve(dmode, dreg, size);
        writeEA(size, val);
        logicFlags(val, size);
    }

    private void group4(int op) {
        int mode = (op >> 3) & 7, reg = op & 7;
        switch (op) {
            case 0x4e75 -> {
                pc = pop32();
                return;
            }
            case 0x4e71 -> {
                return;
            }
            case 0x4e77 -> { // RTR
                setCcr(mem.read16(a[7]));
                a[7] += 2;
                pc = pop32();
                return;
            }
            case 0x4e73 -> { // RTE
                a[7] += 2;
                pc = pop32();
                return;
            }
            default -> {
            }
        }
        if ((op & 0xfff8) == 0x4e50) { // LINK
            push32(a[reg]);
            a[reg] = a[7];
            a[7] += (short) fetch16();
            return;
        }
        if ((op & 0xfff8) == 0x4e58) { // UNLK
            a[7] = a[reg];
            a[reg] = pop32();
            return;
        }
        if ((op & 0xffc0) == 0x4e80 || (op & 0xffc0) == 0x4ec0) { // JSR / JMP
            resolve(mode, reg, 4);
            int target = eaAddr;
            if ((op & 0xffc0) == 0x4e80) {
                push32(pc);
            }
            pc = target;
            return;
        }
        if ((op & 0xf1c0) == 0x41c0) { // LEA
            resolve(mode, reg, 4);
            a[(op >> 9) & 7] = eaAddr;
            return;
        }
        if ((op & 0xffc0) == 0x4800) { // NBCD
            resolve(mode, reg, 1);
            writeEA(1, sbcd(readEA(1), 0));
            return;
        }
        if ((op & 0xfff8) == 0x4840) { // SWAP
            int val = d[reg];
            val = (val << 16) | (val >>> 16);
            d[reg] = val;
            logicFlags(val, 4);
            return;
        }
        if ((op & 0xffc0) == 0x4840) { // PEA
            resolve(mode, reg, 4);
            push32(eaAddr);
            return;
        }
        if ((op & 0xfff8) == 0x4880) { // EXT.W
            int val = (byte) d[reg];
            d[reg] = (d[reg] & 0xffff0000) | (val & 0xffff);
            logicFlags(val, 2);
            return;
        }
        if ((op & 0xfff8) == 0x48c0) { // EXT.L
            d[reg] = (short) d[reg];
            logicFlags(d[reg], 4);
            return;
        }
        if ((op & 0xfb80) == 0x4880) { // MOVEM
            movem(op);
            return;
        }
        if ((op & 0xffc0) == 0x44c0) { // MOVE to CCR
            resolve(mode, reg, 2);
            setCcr(readEA(2));
            return;
        }
        if ((op & 0xffc0) == 0x46c0) { // MOVE to SR
            resolve(mode, reg, 2);
            setCcr(readEA(2));
            return;
        }
        if ((op & 0xffc0) == 0x40c0) { // MOVE from SR
            resolve(mode, reg, 2);
            writeEA(2, 0x2000 | ccr());
            return;
        }
        if ((op & 0xffc0) == 0x4ac0) { // TAS
            resolve(mode, reg, 1);
            int val = readEA(1);
            logicFlags(val, 1);
            writeEA(1, val | 0x80);
            return;
        }
        int sbits = (op >> 6) & 3;
        if (sbits == 3) {
            throw bad();
        }
        int size = sizeOf(sbits);
        switch (op & 0xff00) {
            case 0x4200 -> { // CLR
                resolve(mode, reg, size);
                writeEA(size, 0);
                n = false;
                z = true;
                v = false;
                c = false;
            }
            case 0x4400 -> { // NEG
                resolve(mode, reg, size);
                writeEA(size, sub(readEA(size), 0, size, false, true));
            }
            case 0x4000 -> { // NEGX
                resolve(mode, reg, size);
                writeEA(size, sub(readEA(size), 0, size, true, true));
            }
            case 0x4600 -> { // NOT
                resolve(mode, reg, size);
                int r = ~readEA(size);
                logicFlags(r, size);
                writeEA(size, r);
            }
            case 0x4a00 -> { // TST
                resolve(mode, reg, size);
                logicFlags(readEA(size), size);
            }
            default -> throw bad();
        }
    }

    private void movem(int op) {
        int mask = fetch16();
        int size = (op & 0x40) != 0 ? 4 : 2;
        int mode = (op >> 3) & 7, reg = op & 7;
        boolean toRegs = (op & 0x0400) != 0;
        if (!toRegs && mode == 4) { // predecrement: mask bit 0 = A7 ... bit 15 = D0
            int addr = a[reg];
            for (int i = 15; i >= 0; i--) {
                if ((mask & (1 << (15 - i))) != 0) {
                    addr -= size;
                    int val = i < 8 ? d[i] : a[i - 8];
                    write(addr, size, val);
                }
            }
            a[reg] = addr;
            return;
        }
        int addr;
        if (mode == 3) {
            addr = a[reg];
        } else {
            resolve(mode, reg, size);
            addr = eaAddr;
        }
        for (int i = 0; i < 16; i++) {
            if ((mask & (1 << i)) == 0) {
                continue;
            }
            if (toRegs) {
                int val = sext(read(addr, size), size);
                if (i < 8) {
                    d[i] = val;
                } else {
                    a[i - 8] = val;
                }
            } else {
                write(addr, size, i < 8 ? d[i] : a[i - 8]);
            }
            addr += size;
        }
        if (mode == 3) {
            a[reg] = addr;
        }
    }

    private void group5(int op) {
        int mode = (op >> 3) & 7, reg = op & 7;
        if (((op >> 6) & 3) == 3) {
            int cc = (op >> 8) & 15;
            if (mode == 1) { // DBcc
                int base = pc;
                int disp = (short) fetch16();
                if (!cond(cc)) {
                    int cnt = ((d[reg] & 0xffff) - 1) & 0xffff;
                    d[reg] = (d[reg] & 0xffff0000) | cnt;
                    if (cnt != 0xffff) {
                        pc = base + disp;
                    }
                }
                return;
            }
            resolve(mode, reg, 1); // Scc
            writeEA(1, cond(cc) ? 0xff : 0);
            return;
        }
        int size = sizeOf((op >> 6) & 3);
        int q = (op >> 9) & 7;
        if (q == 0) {
            q = 8;
        }
        boolean subq = (op & 0x0100) != 0;
        if (mode == 1) { // address register: no flags, whole register
            a[reg] += subq ? -q : q;
            return;
        }
        resolve(mode, reg, size);
        int dst = readEA(size);
        writeEA(size, subq ? sub(q, dst, size, false, true) : add(q, dst, size, false));
    }

    private void branch(int op) {
        int cc = (op >> 8) & 15;
        int base = pc;
        int disp = (byte) op;
        if (disp == 0) {
            disp = (short) fetch16();
        }
        if (cc == 1) { // BSR
            push32(pc);
            pc = base + disp;
            return;
        }
        if (cc == 0 || cond(cc)) {
            pc = base + disp;
        }
    }

    private void groupOr(int op) {
        int mode = (op >> 3) & 7, reg = op & 7, dn = (op >> 9) & 7, opmode = (op >> 6) & 7;
        if (opmode == 3 || opmode == 7) { // DIVU / DIVS
            resolve(mode, reg, 2);
            int div = readEA(2);
            if (div == 0) {
                throw new IllegalStateException("68k: division by zero at " + Integer.toHexString(opAddr));
            }
            long q, r;
            if (opmode == 3) {
                long dividend = d[dn] & 0xffffffffL;
                q = dividend / div;
                r = dividend % div;
                if (q > 0xffff) {
                    v = true;
                    c = false;
                    return;
                }
            } else {
                int s = (short) div;
                q = d[dn] / s;
                r = d[dn] % s;
                if (q < -32768 || q > 32767) {
                    v = true;
                    c = false;
                    return;
                }
            }
            d[dn] = (int) ((r & 0xffff) << 16 | (q & 0xffff));
            setNZ((int) q, 2);
            v = false;
            c = false;
            return;
        }
        if (opmode == 4 && mode <= 1) {
            bcdOp(op, false); // SBCD
            return;
        }
        int size = sizeOf(opmode & 3);
        resolve(mode, reg, size);
        int val = readEA(size);
        int r = val | d[dn];
        logicFlags(r, size);
        if (opmode < 4) {
            d[dn] = size == 4 ? r : (d[dn] & ~mask(size)) | (r & mask(size));
        } else {
            writeEA(size, r);
        }
    }

    private void groupAnd(int op) {
        int mode = (op >> 3) & 7, reg = op & 7, rx = (op >> 9) & 7, opmode = (op >> 6) & 7;
        if (opmode == 3 || opmode == 7) { // MULU / MULS
            resolve(mode, reg, 2);
            int src = readEA(2);
            int r = opmode == 3 ? (src & 0xffff) * (d[rx] & 0xffff) : (short) src * (short) d[rx];
            d[rx] = r;
            logicFlags(r, 4);
            return;
        }
        if ((op & 0xf1f8) == 0xc140) { // EXG Dx,Dy
            int t = d[rx];
            d[rx] = d[reg];
            d[reg] = t;
            return;
        }
        if ((op & 0xf1f8) == 0xc148) { // EXG Ax,Ay
            int t = a[rx];
            a[rx] = a[reg];
            a[reg] = t;
            return;
        }
        if ((op & 0xf1f8) == 0xc188) { // EXG Dx,Ay
            int t = d[rx];
            d[rx] = a[reg];
            a[reg] = t;
            return;
        }
        if (opmode == 4 && mode <= 1) {
            bcdOp(op, true); // ABCD
            return;
        }
        int size = sizeOf(opmode & 3);
        resolve(mode, reg, size);
        int r = readEA(size) & d[rx];
        logicFlags(r, size);
        if (opmode < 4) {
            d[rx] = size == 4 ? r : (d[rx] & ~mask(size)) | (r & mask(size));
        } else {
            writeEA(size, r);
        }
    }

    private void groupAddSub(int op, boolean isAdd) {
        int mode = (op >> 3) & 7, reg = op & 7, rx = (op >> 9) & 7, opmode = (op >> 6) & 7;
        if (opmode == 3 || opmode == 7) { // ADDA / SUBA
            int size = opmode == 3 ? 2 : 4;
            resolve(mode, reg, size);
            int src = sext(readEA(size), size);
            a[rx] = isAdd ? a[rx] + src : a[rx] - src;
            return;
        }
        int size = sizeOf(opmode & 3);
        if (opmode >= 4 && mode <= 1) { // ADDX / SUBX
            int src, dst;
            if (mode == 0) {
                src = d[reg];
                dst = d[rx];
                int r = isAdd ? add(src, dst, size, true) : sub(src, dst, size, true, true);
                d[rx] = size == 4 ? r : (d[rx] & ~mask(size)) | (r & mask(size));
            } else {
                resolve(4, reg, size);
                src = readEA(size);
                resolve(4, rx, size);
                dst = readEA(size);
                writeEA(size, isAdd ? add(src, dst, size, true) : sub(src, dst, size, true, true));
            }
            return;
        }
        resolve(mode, reg, size);
        if (opmode < 4) { // <ea> op Dn -> Dn
            int src = readEA(size);
            int r = isAdd ? add(src, d[rx], size, false) : sub(src, d[rx], size, false, true);
            d[rx] = size == 4 ? r : (d[rx] & ~mask(size)) | (r & mask(size));
        } else { // Dn op <ea> -> <ea>
            int dst = readEA(size);
            writeEA(size, isAdd ? add(d[rx], dst, size, false) : sub(d[rx], dst, size, false, true));
        }
    }

    private void groupCmp(int op) {
        int mode = (op >> 3) & 7, reg = op & 7, rx = (op >> 9) & 7, opmode = (op >> 6) & 7;
        if (opmode == 3 || opmode == 7) { // CMPA
            int size = opmode == 3 ? 2 : 4;
            resolve(mode, reg, size);
            sub(sext(readEA(size), size), a[rx], 4, false, false);
            return;
        }
        int size = sizeOf(opmode & 3);
        if (opmode < 4) { // CMP
            resolve(mode, reg, size);
            sub(readEA(size), d[rx], size, false, false);
            return;
        }
        if (mode == 1) { // CMPM (Ay)+,(Ax)+
            resolve(3, reg, size);
            int src = readEA(size);
            resolve(3, rx, size);
            sub(src, readEA(size), size, false, false);
            return;
        }
        resolve(mode, reg, size); // EOR Dn,<ea>
        int r = readEA(size) ^ d[rx];
        logicFlags(r, size);
        writeEA(size, r);
    }

    private void shift(int op) {
        int sbits = (op >> 6) & 3;
        boolean left = (op & 0x100) != 0;
        int size, type, count;
        if (sbits == 3) { // memory form, word, by 1
            size = 2;
            type = (op >> 9) & 3;
            count = 1;
            resolve((op >> 3) & 7, op & 7, 2);
        } else {
            size = sizeOf(sbits);
            type = (op >> 3) & 3;
            int cr = (op >> 9) & 7;
            count = (op & 0x20) != 0 ? d[cr] & 63 : (cr == 0 ? 8 : cr);
            eaKind = 0;
            eaReg = op & 7;
        }
        int m = mask(size), top = msb(size);
        int val = readEA(size) & m;
        boolean vflag = false;
        if (count == 0) {
            c = type == 2 && x; // ROX with 0 count: C = X
            logicFlagsKeepC(val, size);
            return;
        }
        for (int i = 0; i < count; i++) {
            boolean out;
            switch (type) {
                case 0 -> { // ASL / ASR
                    if (left) {
                        out = (val & top) != 0;
                        int nv = (val << 1) & m;
                        if (((nv ^ val) & top) != 0) {
                            vflag = true;
                        }
                        val = nv;
                    } else {
                        out = (val & 1) != 0;
                        val = ((val >>> 1) | (val & top)) & m;
                    }
                    c = out;
                    x = out;
                }
                case 1 -> { // LSL / LSR
                    if (left) {
                        out = (val & top) != 0;
                        val = (val << 1) & m;
                    } else {
                        out = (val & 1) != 0;
                        val = (val >>> 1) & m;
                    }
                    c = out;
                    x = out;
                }
                case 2 -> { // ROXL / ROXR
                    if (left) {
                        out = (val & top) != 0;
                        val = ((val << 1) | (x ? 1 : 0)) & m;
                    } else {
                        out = (val & 1) != 0;
                        val = ((val >>> 1) | (x ? top : 0)) & m;
                    }
                    c = out;
                    x = out;
                }
                default -> { // ROL / ROR
                    if (left) {
                        out = (val & top) != 0;
                        val = ((val << 1) | (out ? 1 : 0)) & m;
                    } else {
                        out = (val & 1) != 0;
                        val = ((val >>> 1) | (out ? top : 0)) & m;
                    }
                    c = out;
                }
            }
        }
        setNZ(val, size);
        v = vflag;
        writeEA(size, val);
    }

    private void logicFlagsKeepC(int val, int size) {
        setNZ(val, size);
        v = false;
    }
}

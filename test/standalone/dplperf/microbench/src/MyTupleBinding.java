/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 */
package dplperf.microbench.src;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.bind.tuple.TupleOutput;

@SuppressWarnings("unchecked")
public class MyTupleBinding extends TupleBinding {

    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }

    static void writeTwoFieldKey(TupleOutput out, ComplexKey ctk) {
        out.writeInt(ctk.getF0());
        out.writeString(ctk.getF1());
    }

    static ComplexKey readTwoFieldKey(TupleInput in) {
        ComplexKey tfk = null;
        tfk = new ComplexKey();
        tfk.setF0(in.readInt());
        tfk.setF1(in.readString());
        return tfk;
    }

    static void writeAddress(TupleOutput out, Address a) {
        if (a == null) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            out.writeString(a.getCity());
            out.writeString(a.getState());
            out.writeInt(a.getZip());
        }
    }

    static Address readAddress(TupleInput in) {
        Address a = null;
        if (!in.readBoolean()) {
            String city = in.readString();
            String street = in.readString();
            int zip = in.readInt();
            a = new Address(city, street, zip);
        }
        return a;
    }

    static void writeBasic(TupleOutput out, BasicEntity b) {
        if (b == null) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            out.writeLong(b.getId());
            out.writeString(b.getBasicOne());
            out.writeDouble(b.getBasicTwo());
            out.writeString(b.getBasicThree());
        }
    }

    static BasicEntity readBasic(TupleInput in) {
        BasicEntity b = null;
        if (!in.readBoolean()) {
            long id = in.readLong();
            String one = in.readString();
            double two = in.readDouble();
            String three = in.readString();
            b = new BasicEntity();
            b.setId(id);
            b.setBasicOne(one);
            b.setBasicTwo(two);
            b.setBasicThree(three);
        }
        return b;
    }

    static void writeSimpleTypes(TupleOutput out, SimpleTypesEntity st) {
        if (st == null) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            out.writeBoolean(st.getF0());
            out.writeChar(st.getF1());
            out.writeByte(st.getF2());
            out.writeShort(st.getF3());
            out.writeInt(st.getF4());
            out.writeLong(st.getF5());
            out.writeFloat(st.getF6());
            out.writeDouble(st.getF7());
            out.writeString(st.getF8());
            out.writeBigInteger(st.getF9());
            out.writeLong(st.getF10().getTime()); // java.util.Date
            out.writeBoolean(st.getF11());
            out.writeChar(st.getF12());
            out.writeByte(st.getF13());
            out.writeShort(st.getF14());
            out.writeInt(st.getF15());
            out.writeLong(st.getF16());
            out.writeFloat(st.getF17());
            out.writeDouble(st.getF18());
        }
    }

    static SimpleTypesEntity readSimpleTypes(TupleInput in) {
        SimpleTypesEntity st = null;
        if (!in.readBoolean()) {
            st = new SimpleTypesEntity();
            st.setF0(in.readBoolean());
            st.setF1(in.readChar());
            st.setF2(in.readByte());
            st.setF3(in.readShort());
            st.setF4(in.readInt());
            st.setF5(in.readLong());
            st.setF6(in.readFloat());
            st.setF7(in.readDouble());
            st.setF8(in.readString());
            st.setF9(in.readBigInteger());
            st.setF10(new java.util.Date(in.readLong()));
            st.setF11(in.readBoolean());
            st.setF12(in.readChar());
            st.setF13(in.readByte());
            st.setF14(in.readShort());
            st.setF15(in.readInt());
            st.setF16(in.readLong());
            st.setF17(in.readFloat());
            st.setF18(in.readDouble());
        }
        return st;
    }
    
    static void writePrimitiveTypes(TupleOutput out, PrimitiveTypesEntity st) {
        if (st == null) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            out.writeBoolean(st.getF0());
            out.writeChar(st.getF1());
            out.writeByte(st.getF2());
            out.writeShort(st.getF3());
            out.writeInt(st.getF4());
            out.writeLong(st.getF5());
            out.writeFloat(st.getF6());
            out.writeDouble(st.getF7());
            out.writeBoolean(st.getF8());
            out.writeChar(st.getF9());
            out.writeByte(st.getF10());
            out.writeShort(st.getF11());
            out.writeInt(st.getF12());
            out.writeLong(st.getF13());
            out.writeFloat(st.getF14());
            out.writeDouble(st.getF15());
        }
    }

    static PrimitiveTypesEntity readPrimitiveTypes(TupleInput in) {
        PrimitiveTypesEntity st = null;
        if (!in.readBoolean()) {
            st = new PrimitiveTypesEntity();
            st.setF0(in.readBoolean());
            st.setF1(in.readChar());
            st.setF2(in.readByte());
            st.setF3(in.readShort());
            st.setF4(in.readInt());
            st.setF5(in.readLong());
            st.setF6(in.readFloat());
            st.setF7(in.readDouble());
            st.setF8(in.readBoolean());
            st.setF9(in.readChar());
            st.setF10(in.readByte());
            st.setF11(in.readShort());
            st.setF12(in.readInt());
            st.setF13(in.readLong());
            st.setF14(in.readFloat());
            st.setF15(in.readDouble());
        }
        return st;
    }
    
    static void writeStringType(TupleOutput out, StringTypeEntity st) {
        if (st == null) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            out.writeString(st.getF0());
            out.writeString(st.getF1());
            out.writeString(st.getF2());
            out.writeString(st.getF3());
            out.writeString(st.getF4());
            out.writeString(st.getF5());
            out.writeString(st.getF6());
            out.writeString(st.getF7());
            out.writeString(st.getF8());
            out.writeString(st.getF9());
            out.writeString(st.getF10());
        }
    }

    static StringTypeEntity readStringType(TupleInput in) {
        StringTypeEntity st = null;
        if (!in.readBoolean()) {
            st = new StringTypeEntity();
            st.setF0(in.readString());
            st.setF1(in.readString());
            st.setF2(in.readString());
            st.setF3(in.readString());
            st.setF4(in.readString());
            st.setF5(in.readString());
            st.setF6(in.readString());
            st.setF7(in.readString());
            st.setF8(in.readString());
            st.setF9(in.readString());
            st.setF10(in.readString());
        }
        return st;
    }

    static void writeArrayTypes(TupleOutput out, ArrayTypesEntity at) {
        if (at == null) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            // boolean[] f0
            boolean[] f0 = at.getF0();
            out.writeInt(f0.length);
            for (boolean b : f0) out.writeBoolean(b);
            // char[] f1
            char[] f1 = at.getF1();
            out.writeInt(f1.length);
            out.writeChars(f1);
            // byte[] f2
            byte[] f2 = at.getF2();
            out.writeInt(f2.length);
            for (byte b : f2) out.writeByte(b);
            // short[] f3
            short[] f3 = at.getF3();
            out.writeInt(f3.length);
            for (short s : f3) out.writeShort(s);
            // int[] f4
            int[] f4 = at.getF4();
            out.writeInt(f4.length);
            for (int i : f4) out.writeInt(i);
            // long[] f5
            long[] f5 = at.getF5();
            out.writeLong(f5.length);
            for (long l : f5) out.writeLong(l);
            // float[] f6
            float[] f6 = at.getF6();
            out.writeInt(f6.length);
            for (float f : f6) out.writeFloat(f);
            // double[] f7
            double[] f7 = at.getF7();
            out.writeInt(f7.length);
            for (double d : f7) out.writeDouble(d);
            // String[] f8
            String[] f8 = at.getF8();
            out.writeInt(f8.length);
            for (String s : f8) out.writeString(s);
            // Address[] f9
            Address[] f9 = at.getF9();
            out.writeInt(f9.length);
            for (Address a : f9) writeAddress(out, a);
        }
    }

    static ArrayTypesEntity readArrayTypes(TupleInput in) {
        ArrayTypesEntity at = null;
        if (!in.readBoolean()) {
            at = new ArrayTypesEntity();
            // boolean
            int len = in.readInt();
            boolean[] za = new boolean[len];
            for (int i = 0; i < len; i++) za[i] = in.readBoolean();
            at.setF0(za);
            // char
            len = in.readInt();
            char[] ca = new char[len];
            in.readChars(ca);
            at.setF1(ca);
            // byte
            len = in.readInt();
            byte[] ba = new byte[len];
            for (int i = 0; i < len; i++) ba[i] = in.readByte();
            at.setF2(ba);
            // short
            len = in.readInt();
            short[] sa = new short[len];
            for (int i = 0; i < len; i++) sa[i] = in.readShort();
            at.setF3(sa);
            // int
            len = in.readInt();
            int[] ia = new int[len];
            for (int i = 0; i < len; i++) ia[i] = in.readInt();
            at.setF4(ia);
            // long
            len = in.readInt();
            long[] la = new long[len];
            for (int i = 0; i < len; i++) la[i] = in.readLong();
            at.setF5(la);
            // float
            len = in.readInt();
            float[] fa = new float[len];
            for (int i = 0; i < len; i++) fa[i] = in.readFloat();
            at.setF6(fa);
            // double
            len = in.readInt();
            double[] da = new double[len];
            for (int i = 0; i < len; i++) da[i] = in.readDouble();
            at.setF7(da);
            // String
            len = in.readInt();
            String[] ssa = new String[len];
            for (int i = 0; i < len; i++) ssa[i] = in.readString();
            at.setF8(ssa);
            // Address
            len = in.readInt();
            Address[] aa = new Address[len];
            for (int i = 0; i < len; i++) aa[i] = readAddress(in);
            at.setF9(aa);
        }
        return at;
    }

    static void writeEnum(TupleOutput out, Enum en) {
        if (en == null) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            out.writeString(en.getClass().getName());
            out.writeString(en.name());
        }
    }

    static Enum readEnum(TupleInput in) {
        Enum en = null;
        if (!in.readBoolean()) {
            try {
                Class enumType = Class.forName(in.readString());
                String name = in.readString();
                en = Enum.valueOf(enumType, name);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        return en;
    }

    static void writeEnumTypes(TupleOutput out, EnumTypesEntity et) {
        if (et == null) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            writeEnum(out, et.getF0());
            writeEnum(out, et.getF1());
            writeObject(out, et.getF2());
        }
    }

    static EnumTypesEntity readEnumTypes(TupleInput in) {
        EnumTypesEntity et = null;
        if (!in.readBoolean()) {
            et = new EnumTypesEntity();
            et.setF0((Thread.State) readEnum(in));
            et.setF1((MyEnum) readEnum(in));
            et.setF2(readObject(in));
        }
        return et;
    }

    static void writeProxyTypes(TupleOutput out, ProxyTypesEntity pt) {
        if (pt == null) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            // Set<Integer> f0
            Set<Integer> f0 = pt.getF0();
            int size = f0.size();
            out.writeInt(size);
            for (int i : f0) out.writeInt(i);
            // Set<Integer> f1
            Set<Integer> f1 = pt.getF1();
            size = f1.size();
            out.writeInt(size);
            for (int i : f1) out.writeInt(i);
            // Object f2
            writeObject(out, pt.getF2());
            // HashMap<String,Integer> f3
            Map<String,Integer> f3 = pt.getF3();
            size = f3.size();
            out.writeInt(size);
            for (Map.Entry<String,Integer> e : f3.entrySet()) {
                out.writeString(e.getKey());
                out.writeInt(e.getValue());
            }
            // TreeMap<String,Address> f4
            Map<String,Address> f4 = pt.getF4();
            size = f4.size();
            out.writeInt(size);
            for (Map.Entry<String,Address> e : f4.entrySet()) {
                out.writeString(e.getKey());
                writeAddress(out, e.getValue());
            }
            // List<Integer> f5
            List<Integer> f5 = pt.getF5();
            size = f5.size();
            out.writeInt(size);
            for (int i : f5) out.writeInt(i);
            // LinkedList<Integer> f6
            List<Integer> f6 = pt.getF6();
            size = f6.size();
            out.writeInt(size);
            for (int i : f6) out.writeInt(i);
        }
    }

    static ProxyTypesEntity readProxyTypes(TupleInput in) {
        ProxyTypesEntity pt = null;
        if (!in.readBoolean()) {
            pt = new ProxyTypesEntity();
            // Set<Integer> f0
            Set<Integer> f0 = new HashSet<Integer>();
            int size = in.readInt();
            for (int i = 0; i < size; i++) f0.add(in.readInt());
            pt.setF0(f0);
            // Set<Integer> f1
            Set<Integer> f1 = new TreeSet<Integer>();
            size = in.readInt();
            for (int i = 0; i < size; i++) f1.add(in.readInt());
            pt.setF1(f1);
            // Object f2
            pt.setF2(readObject(in));
            // HashMap<String,Integer> f3
            HashMap<String,Integer> f3 = new HashMap<String,Integer>();
            size = in.readInt();
            for (int i = 0; i < size; i++)
                f3.put(in.readString(), in.readInt());
            pt.setF3(f3);
            // TreeMap<String,Address> f4
            TreeMap<String,Address> f4 = new TreeMap<String,Address>();
            size = in.readInt();
            for (int i = 0; i < size; i++)
                f4.put(in.readString(), readAddress(in));
            pt.setF4(f4);
            // List<Integer> f5
            List<Integer> f5 = new ArrayList<Integer>();
            size = in.readInt();
            for (int i = 0; i < size; i++) f5.add(in.readInt());
            pt.setF5(f5);
            // LinkedList<Integer> f6
            LinkedList<Integer> f6 = new LinkedList<Integer>();
            size = in.readInt();
            for (int i = 0; i < size; i++) f6.add(in.readInt());
            pt.setF6(f6);
        }
        return pt;
    }

    static void writeSubclass(TupleOutput out, SubclassEntity sub) {
        if (sub == null) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            out.writeLong(sub.getId());
            out.writeString(sub.getBasicOne());
            out.writeDouble(sub.getBasicTwo());
            out.writeString(sub.getBasicThree());
            out.writeString(sub.getSubclasOne());
            out.writeBoolean(sub.isSubclassTwo());
        }
    }

    static SubclassEntity readSubclass(TupleInput in) {
        SubclassEntity sub = null;
        if (!in.readBoolean()) {
            long id = in.readLong();
            String bOne = in.readString();
            double bTwo = in.readDouble();
            String bThree = in.readString();
            String subOne = in.readString();
            boolean subTwo = in.readBoolean();
            sub = new SubclassEntity();
            sub.setId(id);
            sub.setBasicOne(bOne);
            sub.setBasicTwo(bTwo);
            sub.setBasicThree(bThree);
            sub.setSubclassOne(subOne);
            sub.setSubclassTwo(subTwo);
        }
        return sub;
    }
    
    static void writeCollection(TupleOutput out, Object obj) {
        Collection col = (Collection) obj;
        if (col.isEmpty()) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            Object[] ea = col.toArray();
            out.writeString(obj.getClass().getName());
            out.writeInt(ea.length);
            for (Object e : ea) {
                writeObject0(out, e);
            }
        }
    }

    static Collection readCollection(TupleInput in) {
        Collection col = null;
        if (!in.readBoolean()) {
            try {
                String colType = in.readString(); // name of Collection type
                int size = in.readInt();
                col = (Collection) Class.forName(colType).newInstance();
                for (int i = 0; i < size; i++) {
                    col.add(readObject0(in));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return col;
    }
    
    static void writeMap(TupleOutput out, Object obj) {
        Map map = (Map) obj;
        if (map.isEmpty()) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            out.writeString(obj.getClass().getName());
            out.writeInt(map.size());
            for (Object o : map.entrySet()) {
                Map.Entry entry = (Map.Entry) o;
                writeObject0(out, entry.getKey());
                writeObject0(out, entry.getValue());
            }
        }
    }

    static Map readMap(TupleInput in) {
        Map map = null;
        if (!in.readBoolean()) {
            try {
                String mapType = in.readString(); // name of map type
                int size = in.readInt();
                map = (Map) Class.forName(mapType).newInstance();
                for (int i = 0; i < size; i++) {
                    map.put(readObject0(in), readObject0(in));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return map;
    }
    

    static void writeObject0(TupleOutput out, Object o) {
        // Use a char to indicate the class signature of the instance.
        char signature;
        if (o instanceof Boolean) {
            signature = 'Z';
            out.writeChar(signature);
            out.writeBoolean((Boolean) o);
        } else if (o instanceof Byte) {
            signature = 'B';
            out.writeChar(signature);
            out.writeByte((Byte) o);
        } else if (o instanceof Character) {
            signature = 'C';
            out.writeChar(signature);
            out.writeChar((Character) o);
        } else if (o instanceof Short) {
            signature = 'S';
            out.writeChar(signature);
            out.writeShort((Short) o);
        } else if (o instanceof Integer) {
            signature = 'I';
            out.writeChar(signature);
            out.writeInt((Integer) o);
        } else if (o instanceof Long) {
            signature = 'J';
            out.writeChar(signature);
            out.writeLong((Long) o);
        } else if (o instanceof Float) {
            signature = 'F';
            out.writeChar(signature);
            out.writeFloat((Float) o);
        } else if (o instanceof Double) {
            signature = 'D';
            out.writeChar(signature);
            out.writeDouble((Double) o);
        } else if (o instanceof String) {
            signature = '*';
            out.writeChar(signature);
            out.writeString((String) o);
        } else if (o instanceof BigInteger) {
            signature = '#';
            out.writeChar(signature);
            out.writeBigInteger((BigInteger) o);
        } else if (o instanceof Address) {
            signature = '&';
            out.writeChar(signature);
            writeAddress(out, (Address) o);
        } else {
            throw new IllegalArgumentException
                ("unsupported obejct type: " + o.getClass().getName());
        }
    }
    
    static Object readObject0(TupleInput in) {
        Object o = null;
        char signature = in.readChar();
        switch (signature) {
            case 'Z': o = in.readBoolean(); break;
            case 'B': o = in.readByte(); break;
            case 'C': o = in.readChar(); break;
            case 'S': o = in.readShort(); break;
            case 'I': o = in.readInt(); break;
            case 'L': o = in.readLong(); break;
            case 'F': o = in.readFloat(); break;
            case 'D': o = in.readDouble(); break;
            case '*': o = in.readString(); break;
            case '#': o = in.readBigInteger(); break;
            case '&': o = readAddress(in); break;
            default:
                throw new IllegalArgumentException
                    ("unsupported object type: " + signature);
        }
        return o;
    }
    
    /**
     * Write objects to JE databases.
     *
     * According to the Sun JDK implementation, primitive data types
     * have class signatures as follows:
     * 'Z' - Boolean
     * 'B' - Byte
     * 'C' - Character
     * 'S' - Short
     * 'I' - Integer
     * 'J' - Long
     * 'F' - Float
     * 'D' - Double
     *
     * Here we define the reference types with its class signature like below:
     * '*' - String
     * '#' - BigInteger
     * '@' - TwoFieldKey
     * '&' - Address
     * '(' - BasicEntity
     * ')' - SubclassEntity
     * '$' - SimpleTypesEntity
     * '[' - ArrayTypesEntity
     * '~' - ProxyTypesEntity
     * '-' - Enum
     * '=' - EnumTypesEntity
     * '+' - Collection and all its subclasses
     * '|' - Map and all its derived classes
     */
    static void writeObject(TupleOutput out, Object o) {
        if (o == null) {
            out.writeBoolean(true);
        } else {
            out.writeBoolean(false);
            /* Use a char to indicate the class signature of the instance. */
            char signature;
            if (o instanceof Boolean) {
                signature = 'Z';
                out.writeChar(signature);
                out.writeBoolean((Boolean) o);
            } else if (o instanceof Byte) {
                signature = 'B';
                out.writeChar(signature);
                out.writeByte((Byte) o);
            } else if (o instanceof Character) {
                signature = 'C';
                out.writeChar(signature);
                out.writeChar((Character) o);
            } else if (o instanceof Short) {
                signature = 'S';
                out.writeChar(signature);
                out.writeShort((Short) o);
            } else if (o instanceof Integer) {
                signature = 'I';
                out.writeChar(signature);
                out.writeInt((Integer) o);
            } else if (o instanceof Long) {
                signature = 'J';
                out.writeChar(signature);
                out.writeLong((Long) o);
            } else if (o instanceof Float) {
                signature = 'F';
                out.writeChar(signature);
                out.writeFloat((Float) o);
            } else if (o instanceof Double) {
                signature = 'D';
                out.writeChar(signature);
                out.writeDouble((Double) o);
            } else if (o instanceof String) {
                signature = '*';
                out.writeChar(signature);
                out.writeString((String) o);
            } else if (o instanceof BigInteger) {
                signature = '#';
                out.writeChar(signature);
                out.writeBigInteger((BigInteger) o);
            } else if (o instanceof ComplexKey) {
                signature = '@';
                out.writeChar(signature);
                writeTwoFieldKey(out, (ComplexKey) o);
            } else if (o instanceof Address) {
                signature = '&';
                out.writeChar(signature);
                writeAddress(out, (Address) o);
            } else if (o instanceof BasicEntity) {
                if (o instanceof SubclassEntity) {
                    signature = ')';
                    out.writeChar(signature);
                    writeSubclass(out, (SubclassEntity) o);
                } else {
                    signature = '(';
                    out.writeChar(signature);
                    writeBasic(out, (BasicEntity) o);
                }
            } else if (o instanceof SimpleTypesEntity) {
                signature = '$';
                out.writeChar(signature);
                writeSimpleTypes(out, (SimpleTypesEntity) o);
            } else if (o instanceof ArrayTypesEntity) {
                signature = '[';
                out.writeChar(signature);
                writeArrayTypes(out, (ArrayTypesEntity) o);
            } else if (o instanceof ProxyTypesEntity) {
                signature = '~';
                out.writeChar(signature);
                writeProxyTypes(out, (ProxyTypesEntity) o);
            } else if (o instanceof Enum) {
                signature = '-';
                out.writeChar(signature);
                writeEnum(out, (Enum) o);
            } else if (o instanceof EnumTypesEntity) {
                signature = '=';
                out.writeChar(signature);
                writeEnumTypes(out, (EnumTypesEntity) o);
            } else if (o instanceof Collection) {
                signature = '+';
                out.writeChar(signature);
                writeCollection(out, o);
            } else if (o instanceof Map) {
                signature = '=';
                out.writeChar(signature);
                writeMap(out, o);
            } else {
                throw new IllegalArgumentException
                    ("unsupported object type: " + o.getClass().getName());
            }
        }
    }

    /**
     * Construct objects by reading from JE databases.
     *
     * According to the Sun JDK implementation, primitive data types
     * have class signatures as follows:
     * 'Z' - Boolean
     * 'B' - Byte
     * 'C' - Character
     * 'S' - Short
     * 'I' - Integer
     * 'J' - Long
     * 'F' - Float
     * 'D' - Double
     *
     * Here we define the reference types with its class signature like below:
     * '*' - String
     * '#' - BigInteger
     * '@' - TwoFieldKey
     * '&' - Address
     * '(' - BasicEntity
     * ')' - SubclassEntity
     * '$' - SimpleTypesEntity
     * '[' - ArrayTypesEntity
     * '~' - ProxyTypesEntity
     * '-' - Enum
     * '=' - EnumTypesEntity
     * '+' - Collection and all its subclasses
     * '|' - Map and all its derived classes
     */
    static Object readObject(TupleInput in) {
        Object o = null;
        if (!in.readBoolean()) {
            char signature = in.readChar();
            switch (signature) {
                case 'Z': o = in.readBoolean(); break;
                case 'B': o = in.readByte(); break;
                case 'C': o = in.readChar(); break;
                case 'S': o = in.readShort(); break;
                case 'I': o = in.readInt(); break;
                case 'L': o = in.readLong(); break;
                case 'F': o = in.readFloat(); break;
                case 'D': o = in.readDouble(); break;
                case '*': o = in.readString(); break;
                case '#': o = in.readBigInteger(); break;
                case '@': o = readTwoFieldKey(in); break;
                case '&': o = readAddress(in); break;
                case '(': o = readBasic(in); break;
                case ')': o = readSubclass(in); break;
                case '$': o = readSimpleTypes(in); break;
                case '[': o = readArrayTypes(in); break;
                case '~': o = readProxyTypes(in); break;
                case '-': o = readEnum(in); break;
                case '=': o = readEnumTypes(in); break;
                case '+': o = readCollection(in); break;
                case '|': o = readMap(in); break;
                default:
                    throw new IllegalArgumentException
                        ("unsupported object type: " + signature);
            }
        }
        return o;
    }
}

class MySimpleTypesBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (SimpleTypesEntity) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, SimpleTypesEntity o) {  
        writeSimpleTypes(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readSimpleTypes(in);
        return o;
    }
}

class MyPrimitiveTypesBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (PrimitiveTypesEntity) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, PrimitiveTypesEntity o) {  
        writePrimitiveTypes(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readPrimitiveTypes(in);
        return o;
    }
}

class MyStringTypeBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (StringTypeEntity) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, StringTypeEntity o) {  
        writeStringType(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readStringType(in);
        return o;
    }
}

class MyComplexKeyBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (ComplexKey) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, ComplexKey o) {
        writeTwoFieldKey(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readTwoFieldKey(in);
        return o;
    }
}

class MyAddressBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (Address) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, Address o) {
        writeAddress(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readAddress(in);
        return o;
    }
}

class MyBasicBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (BasicEntity) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, BasicEntity o) {
        writeBasic(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readBasic(in);
        return o;
    }
}

class MySubclassBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (SubclassEntity) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, SubclassEntity o) {
        writeSubclass(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readSubclass(in);
        return o;
    }
}

class MyArrayTypesBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (ArrayTypesEntity) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, ArrayTypesEntity o) {
        writeArrayTypes(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readArrayTypes(in);
        return o;
    }
}

class MyProxyTypesBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (ProxyTypesEntity) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, ProxyTypesEntity o) {
        writeProxyTypes(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readProxyTypes(in);
        return o;
    }
}

class MyEnumBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (Enum) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, Enum o) {
        writeEnum(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readEnum(in);
        return o;
    }
}

class MyEnumTypesBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (EnumTypesEntity) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, EnumTypesEntity o) {
        writeEnumTypes(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readEnumTypes(in);
        return o;
    }
}

class MyCollectionBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (Collection) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, Collection o) {
        writeCollection(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readCollection(in);
        return o;
    }
}

class MyMapBinding extends MyTupleBinding {
    
    // Write an object to a TupleOutput
    public void objectToEntry(Object object, TupleOutput to) {
        writeObject(to, (Map) object);
    }

    // Convert a TupleInput to an object
    public Object entryToObject(TupleInput ti) {
        return readObject(ti);
    }
    
    static void writeObject(TupleOutput out, Map o) {
        writeMap(out, o);
    }
    
    static Object readObject(TupleInput in) {
        Object o = readMap(in);
        return o;
    }
}
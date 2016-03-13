/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002,2008 Oracle and/or its affiliates.  All rights reserved.
 */
package dplperf.microbench.src;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.sleepycat.persist.model.Entity;
import com.sleepycat.persist.model.KeyField;
import com.sleepycat.persist.model.Persistent;
import com.sleepycat.persist.model.PrimaryKey;

@Entity
public class MyEntity {
    @PrimaryKey
    int key;
    String data;

    public MyEntity() {}
    
    public MyEntity(int key) {
        this.key = key;
        data = "The quick fox jumps over the lazy dog.";
    }

    public int getKey() {
        return key;
    }

    public void setKey(int key) {
        this.key = key;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
    
    public void modify() {
        this.data = "The lazy dog jumps over the quick fox.";
    }
    
    @Override
    public String toString() {
        return "SingleEntity: (" + key + "," + data + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof MyEntity) {
            MyEntity oo = (MyEntity) o;
            return (this.key == oo.key) && (this.data.equals(oo.data));
        }
        return false;
    }
}

@Persistent
class ComplexKey {
    @KeyField(1)
    int f0;
    @KeyField(2)
    String f1;

    ComplexKey() {} // for bindings
    
    ComplexKey(int f0) {
        this.f0 = f0;
        f1 = "The quick fox jumps over the lazy dog.";
    }
    
    ComplexKey(int f0, String f1) {
        this.f0 = f0;
        this.f1 = f1;
    }
    
    public String getF1() {
        return f1;
    }

    public void setF1(String f1) {
        this.f1 = f1;
    }

    public int getF0() {
        return f0;
    }

    public void setF0(int f0) {
        this.f0 = f0;
    }

    @Override
    public String toString() {
        return "ComplexKey: (" + f0 + "," + f1 + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof ComplexKey) {
            ComplexKey oo = (ComplexKey) o;
            return (this.f0 == oo.f0) && (this.f1.equals(oo.f1));
        }
        return false;
    }
}

@Entity
class BasicEntity {
    
    @PrimaryKey
    ComplexKey key;
    
    protected long id;
    protected String one;
    protected double two;
    protected String three;

    BasicEntity() { }

    BasicEntity(int i) {
        key = new ComplexKey(i);
        id = 0;
        one = "one";
        two = .2d;
        three = "three";
    }
    
    public ComplexKey getKey() {
        return key;
    }

    public void setKey(ComplexKey key) {
        this.key = key;
    }

    public long getId() {
        return id;
    }
    
    public void setId(long id) {
        this.id = id;
    }

    public String getBasicOne() {
        return one;
    }
    
    public void setBasicOne(String one) {
        this.one = one;
    }
    
    public double getBasicTwo() {
        return two;
    }

    public void setBasicTwo(double two) {
        this.two = two;
    }

    public String getBasicThree() {
        return three;
    }
    
    public void setBasicThree(String three) {
        this.three = three;
    }

    public void modify() {
        id++;
        one += "1";
        two = id;
        three += "3";
    }

    @Override
    public String toString() {
        return "BasicEntity: ( key: (" + key + ")," + id + "," + one + "," +
            two + "," + three + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof BasicEntity) {
            BasicEntity oo = (BasicEntity) o;
            return (this.key.equals(oo.key)) && (this.id == oo.id) && 
                   (this.one.equals(oo.one)) && (this.two == oo.two) && 
                   (this.three.equals(oo.three));
        }
        return false;
    }
}

@Entity
class SimpleTypesEntity {
    
    @PrimaryKey
    ComplexKey key;
    
    private boolean f0;
    private char f1;
    private byte f2;
    private short f3;
    private int f4;
    private long f5;
    private float f6;
    private double f7;
    private String f8;
    private BigInteger f9;
    private Date f10;
    private Boolean f11;
    private Character f12;
    private Byte f13;
    private Short f14;
    private Integer f15;
    private Long f16;
    private Float f17;
    private Double f18;
    
    SimpleTypesEntity() { }
    
    SimpleTypesEntity(int i) {
        key = new ComplexKey(i);
        f0 = true;
        f1 = 'a';
        f2 = 123;
        f3 = 123;
        f4 = 123;
        f5 = 123;
        f6 = 123.4f;
        f7 = 123.4;
        f8 = "xxx";
        f9 = BigInteger.valueOf(123);
        f10 = new Date();
        f11 = true;
        f12 = 'a';
        f13 = 123;
        f14 = 123;
        f15 = 123;
        f16 = 123L;
        f17 = 123.4f;
        f18 = 123.4;
    }
    
    public ComplexKey getKey() {
        return key;
    }

    public void setKey(ComplexKey key) {
        this.key = key;
    }
    
    public boolean getF0() {
        return f0;
    }

    public void setF0(boolean f0) {
        this.f0 = f0;
    }

    public char getF1() {
        return f1;
    }

    public void setF1(char f1) {
        this.f1 = f1;
    }

    public byte getF2() {
        return f2;
    }

    public void setF2(byte f2) {
        this.f2 = f2;
    }

    public short getF3() {
        return f3;
    }

    public void setF3(short f3) {
        this.f3 = f3;
    }

    public int getF4() {
        return f4;
    }

    public void setF4(int f4) {
        this.f4 = f4;
    }

    public long getF5() {
        return f5;
    }

    public void setF5(long f5) {
        this.f5 = f5;
    }

    public float getF6() {
        return f6;
    }

    public void setF6(float f6) {
        this.f6 = f6;
    }

    public double getF7() {
        return f7;
    }

    public void setF7(double f7) {
        this.f7 = f7;
    }

    public String getF8() {
        return f8;
    }

    public void setF8(String f8) {
        this.f8 = f8;
    }

    public BigInteger getF9() {
        return f9;
    }

    public void setF9(BigInteger f9) {
        this.f9 = f9;
    }

    public Date getF10() {
        return f10;
    }

    public void setF10(Date f10) {
        this.f10 = f10;
    }

    public Boolean getF11() {
        return f11;
    }

    public void setF11(Boolean f11) {
        this.f11 = f11;
    }

    public Character getF12() {
        return f12;
    }

    public void setF12(Character f12) {
        this.f12 = f12;
    }

    public Byte getF13() {
        return f13;
    }

    public void setF13(Byte f13) {
        this.f13 = f13;
    }

    public Short getF14() {
        return f14;
    }

    public void setF14(Short f14) {
        this.f14 = f14;
    }

    public Integer getF15() {
        return f15;
    }

    public void setF15(Integer f15) {
        this.f15 = f15;
    }

    public Long getF16() {
        return f16;
    }

    public void setF16(Long f16) {
        this.f16 = f16;
    }

    public Float getF17() {
        return f17;
    }

    public void setF17(Float f17) {
        this.f17 = f17;
    }

    public Double getF18() {
        return f18;
    }

    public void setF18(Double f18) {
        this.f18 = f18;
    }

    public void modify() {
        f0 = !f0;
        f1 += 1;
        f2 += 2;
        f3 += 3;
        f4 += 4;
        f5 += 5;
        f6 += .6f;
        f7 += .7d;
        f8 += "f8";
        f9.add(BigInteger.valueOf(9));
        f10 = new Date(1228899523870l);
        f11 = f0;
        f12 = 'a' + 12;
        f13 = 127;
        f14 = 137;
        f15 += 15;
        f16 += 16;
        f17 += .17f;
        f18 += .18d;
    }
    
    @Override
    public String toString() {
        return "SimpleTypesEntity: (ComplexKey: (" + key + ")," + f0 + "," +
            f1 + "," + f2 + "," + f3 + "," + f4 + "," + f5 + "," + f6 + "," +
            f7 + "," + f8 + "," + f9 + "," + f10 + "," + f11 + "," + f12 +
            "," + f13 + "," + f14 + "," + f15 + "," + f16 + "," + f17 + "," +
            f18 + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof SimpleTypesEntity) {
            SimpleTypesEntity oo = (SimpleTypesEntity) o;
            return (this.key.equals(oo.key)) && (this.f0 == oo.f0) && 
                   (this.f1 == oo.f1) && (this.f2 == oo.f2) && 
                   (this.f3 == oo.f3) && (this.f4 == oo.f4) && 
                   (this.f5 == oo.f5) && (this.f6 == oo.f6) && 
                   (this.f7 == oo.f7) && (this.f8.equals(oo.f8)) && 
                   (this.f9.equals(oo.f9)) && (this.f10.equals(oo.f10)) && 
                   (this.f11.equals(oo.f11)) && (this.f12.equals(oo.f12)) && 
                   (this.f13.equals(oo.f13)) && (this.f14.equals(oo.f14)) && 
                   (this.f15.equals(oo.f15)) && (this.f16.equals(oo.f16)) && 
                   (this.f17.equals(oo.f17)) && (this.f18.equals(oo.f18));
        }
        return false;
    }
}

@Entity
class PrimitiveTypesEntity {
    
    @PrimaryKey
    ComplexKey key;
    
    private boolean f0;
    private char f1;
    private byte f2;
    private short f3;
    private int f4;
    private long f5;
    private float f6;
    private double f7;
    private boolean f8;
    private char f9;
    private byte f10;
    private short f11;
    private int f12;
    private long f13;
    private float f14;
    private double f15;
    
    PrimitiveTypesEntity() { }
    
    PrimitiveTypesEntity(int i) {
        key = new ComplexKey(i);
        f0 = true;
        f1 = 'a';
        f2 = 123;
        f3 = 123;
        f4 = 123;
        f5 = 123;
        f6 = 123.4f;
        f7 = 123.4;
        f8 = false;
        f9 = 'b';
        f10 = 124;
        f11 = 1234;
        f12 = 1234;
        f13 = 1234L;
        f14 = 1234.4f;
        f15 = 1234.4;
    }
    
    public ComplexKey getKey() {
        return key;
    }

    public void setKey(ComplexKey key) {
        this.key = key;
    }
    
    public boolean getF0() {
        return f0;
    }

    public void setF0(boolean f0) {
        this.f0 = f0;
    }

    public char getF1() {
        return f1;
    }

    public void setF1(char f1) {
        this.f1 = f1;
    }

    public byte getF2() {
        return f2;
    }

    public void setF2(byte f2) {
        this.f2 = f2;
    }

    public short getF3() {
        return f3;
    }

    public void setF3(short f3) {
        this.f3 = f3;
    }

    public int getF4() {
        return f4;
    }

    public void setF4(int f4) {
        this.f4 = f4;
    }

    public long getF5() {
        return f5;
    }

    public void setF5(long f5) {
        this.f5 = f5;
    }

    public float getF6() {
        return f6;
    }

    public void setF6(float f6) {
        this.f6 = f6;
    }

    public double getF7() {
        return f7;
    }

    public void setF7(double f7) {
        this.f7 = f7;
    }

    public boolean getF8() {
        return f8;
    }

    public void setF8(boolean f8) {
        this.f8 = f8;
    }

    public char getF9() {
        return f9;
    }

    public void setF9(char f9) {
        this.f9 = f9;
    }

    public byte getF10() {
        return f10;
    }

    public void setF10(byte f10) {
        this.f10 = f10;
    }

    public short getF11() {
        return f11;
    }

    public void setF11(short f11) {
        this.f11 = f11;
    }

    public int getF12() {
        return f12;
    }

    public void setF12(int f12) {
        this.f12 = f12;
    }

    public long getF13() {
        return f13;
    }

    public void setF13(long f13) {
        this.f13 = f13;
    }

    public float getF14() {
        return f14;
    }

    public void setF14(float f14) {
        this.f14 = f14;
    }

    public double getF15() {
        return f15;
    }

    public void setF15(double f15) {
        this.f15 = f15;
    }

    public void modify() {
        f0 = !f0;
        f1 += 1;
        f2 += 2;
        f3 += 3;
        f4 += 4;
        f5 += 5;
        f6 += .6f;
        f7 += .7d;
        f8 = !f8;
        f9 += 1;
        f10 += 2;
        f11 += 3;
        f12 += 4;
        f13 += 5;
        f14 = 137;
        f15 += 15;
    }
    
    @Override
    public String toString() {
        return "PrimitiveTypesEntity: (ComplexKey: (" + key + ")," + f0 + "," +
            f1 + "," + f2 + "," + f3 + "," + f4 + "," + f5 + "," + f6 + "," +
            f7 + "," + f8 + "," + f9 + "," + f10 + "," + f11 + "," + f12 +
            "," + f13 + "," + f14 + "," + f15 + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof PrimitiveTypesEntity) {
            PrimitiveTypesEntity oo = (PrimitiveTypesEntity) o;
            return (this.key.equals(oo.key)) && (this.f0 == oo.f0) && 
                   (this.f1 == oo.f1) && (this.f2 == oo.f2) && 
                   (this.f3 == oo.f3) && (this.f4 == oo.f4) && 
                   (this.f5 == oo.f5) && (this.f6 == oo.f6) && 
                   (this.f7 == oo.f7) && (this.f8 == oo.f8) && 
                   (this.f9 == oo.f9) && (this.f10 == oo.f10) && 
                   (this.f11 == oo.f11) && (this.f12 == oo.f12) && 
                   (this.f13 == oo.f13) && (this.f14 == oo.f14) && 
                   (this.f15 == oo.f15);
        }
        return false;
    }
}

@Entity
class StringTypeEntity {
    
    @PrimaryKey
    ComplexKey key;
    
    private String f0;
    private String f1;
    private String f2;
    private String f3;
    private String f4;
    private String f5;
    private String f6;
    private String f7;
    private String f8;
    private String f9;
    private String f10;
    
    StringTypeEntity() { }
    
    StringTypeEntity(int i) {
        key = new ComplexKey(i);
        f0 = "aaaa";
        f1 = "bbbb";
        f2 = "cccc";
        f3 = "dddd";
        f4 = "eeee";
        f5 = "ffff";
        f6 = "gggg";
        f7 = "hhhh";
        f8 = "iiii";
        f9 = "jjjj";
        f10 = "kkkk";
    }
    
    public ComplexKey getKey() {
        return key;
    }

    public void setKey(ComplexKey key) {
        this.key = key;
    }
    
    public String getF0() {
        return f0;
    }

    public void setF0(String f0) {
        this.f0 = f0;
    }

    public String getF1() {
        return f1;
    }

    public void setF1(String f1) {
        this.f1 = f1;
    }

    public String getF2() {
        return f2;
    }

    public void setF2(String f2) {
        this.f2 = f2;
    }

    public String getF3() {
        return f3;
    }

    public void setF3(String f3) {
        this.f3 = f3;
    }

    public String getF4() {
        return f4;
    }

    public void setF4(String f4) {
        this.f4 = f4;
    }

    public String getF5() {
        return f5;
    }

    public void setF5(String f5) {
        this.f5 = f5;
    }

    public String getF6() {
        return f6;
    }

    public void setF6(String f6) {
        this.f6 = f6;
    }

    public String getF7() {
        return f7;
    }

    public void setF7(String f7) {
        this.f7 = f7;
    }

    public String getF8() {
        return f8;
    }

    public void setF8(String f8) {
        this.f8 = f8;
    }

    public String getF9() {
        return f9;
    }

    public void setF9(String f9) {
        this.f9 = f9;
    }

    public String getF10() {
        return f10;
    }

    public void setF10(String f10) {
        this.f10 = f10;
    }

    public void modify() {
        f0 += "f0";
        f1 += "f1";
        f2 += "f2";
        f3 += "f3";
        f4 += "f4";
        f5 += "f5";
        f6 += "f6";
        f7 += "f7";
        f8 += "f8";
        f9 += "f9";
        f10 += "f10";
    }
    
    @Override
    public String toString() {
        return "StringTypeEntity: (ComplexKey: (" + key + ")," + f0 + "," +
            f1 + "," + f2 + "," + f3 + "," + f4 + "," + f5 + "," + f6 + "," +
            f7 + "," + f8 + "," + f9 + "," + f10 + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof StringTypeEntity) {
            StringTypeEntity oo = (StringTypeEntity) o;
            return (this.key.equals(oo.key)) && (this.f0.equals(oo.f0)) && 
                   (this.f1.equals(oo.f1)) && (this.f2.equals(oo.f2)) && 
                   (this.f3.equals(oo.f3)) && (this.f4.equals(oo.f4)) && 
                   (this.f5.equals(oo.f5)) && (this.f6.equals(oo.f6)) && 
                   (this.f7.equals(oo.f7)) && (this.f8.equals(oo.f8)) && 
                   (this.f9.equals(oo.f9)) && (this.f10.equals(oo.f10));
        }
        return false;
    }
}

@Entity
class ArrayTypesEntity {

    @PrimaryKey
    ComplexKey key;
    
    private boolean[] f0;
    private char[] f1;
    private byte[] f2;
    private short[] f3;
    private int[] f4;
    private long[] f5;
    private float[] f6;
    private double[] f7;
    private String[] f8;
    private Address[] f9;
    
    ArrayTypesEntity() { }
    
    ArrayTypesEntity(int i) {
        key = new ComplexKey(i);
        f0 = new boolean[] {false, true};
        f1 = new char[] { 'a', 'b' };
        f2 = new byte[] { 1, 2 };
        f3 = new short[] { 1, 2 };
        f4 = new int[] { 1, 2 };
        f5 = new long[] { 1, 2 };
        f6 = new float[] { 1.1f, 2.2f };
        f7 = new double[] { 1.1, 2,2 };
        f8 = new String[] { "xxx", null, "yyy" };
        f9 = new Address[] { new Address("city1", "state1", 10000),
                             null,
                             new Address("x", "y", 444) };
    }
    
    public ComplexKey getKey() {
        return key;
    }

    public void setKey(ComplexKey key) {
        this.key = key;
    }
    
    public boolean[] getF0() {
        return f0;
    }

    public void setF0(boolean[] f0) {
        this.f0 = f0;
    }

    public char[] getF1() {
        return f1;
    }

    public void setF1(char[] f1) {
        this.f1 = f1;
    }

    public byte[] getF2() {
        return f2;
    }

    public void setF2(byte[] f2) {
        this.f2 = f2;
    }

    public short[] getF3() {
        return f3;
    }

    public void setF3(short[] f3) {
        this.f3 = f3;
    }

    public int[] getF4() {
        return f4;
    }

    public void setF4(int[] f4) {
        this.f4 = f4;
    }

    public long[] getF5() {
        return f5;
    }

    public void setF5(long[] f5) {
        this.f5 = f5;
    }

    public float[] getF6() {
        return f6;
    }

    public void setF6(float[] f6) {
        this.f6 = f6;
    }

    public double[] getF7() {
        return f7;
    }

    public void setF7(double[] f7) {
        this.f7 = f7;
    }

    public String[] getF8() {
        return f8;
    }

    public void setF8(String[] f8) {
        this.f8 = f8;
    }

    public Address[] getF9() {
        return f9;
    }

    public void setF9(Address[] f9) {
        this.f9 = f9;
    }

    public void modify() {
        // boolean[2] f0
        f0[0] =  true;
        f0[1] = false;
        // char[2] f1
        f1[0] = 'c';
        f1[1] = 'd';
        // byte[2] f2
        f2[0] += 2;
        f2[1] += 2;
        // short[2] f3
        f3[0] += 3;
        f3[1] += 3;
        // int[2] f4
        f4[0] += 4;
        f4[1] += 4;
        // long[2] f5
        f5[0] += 5;
        f5[1] += 5;
        // float[2] f6
        f6[0] += .6;
        f6[1] += .6;
        // double[2] f7
        f7[0] += .7;
        f7[1] += .7;
        // String[3] f8
        f8[0] += "f8";
        f8[1] += "f8";
        f8[2] += "f8";
        // Address[3] f9
        f9[0] = new Address("city2", "state2", 20000);
        f9[1] = new Address("city3", "state3", 30000);
        f9[0] = null;
    }
    
    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("ArrayTypes: (");
        
        sb.append("ComplexKey: (").append(key).append("),");
        
        sb.append("boolean[");
        for (boolean b : f0) sb.append(b).append(",");
        sb.append("],");
        
        sb.append("char[");
        for (char c : f1) sb.append(c).append(",");
        sb.append("],");
        
        sb.append("byte[");
        for (byte b : f2) sb.append(b).append(",");
        sb.append("],");
        
        sb.append("short[");
        for (short s : f3) sb.append(s).append(",");
        sb.append("],");
        
        sb.append("int[");
        for (int i : f4) sb.append(i).append(",");
        sb.append("],");
        
        sb.append("long[");
        for (long l : f5) sb.append(l).append(",");
        sb.append("],");
        
        sb.append("float[");
        for (float f : f6) sb.append(f).append(",");
        sb.append("],");
        
        sb.append("double[");
        for (double d : f7) sb.append(d).append(",");
        sb.append("],");

        sb.append("String[");
        for (String s : f8) sb.append(s).append(",");
        sb.append("],");
        
        sb.append("Address[");
        for (Address a : f9) sb.append(a).append(",");
        sb.append("])");
        
        return sb.toString();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof ArrayTypesEntity) {
            ArrayTypesEntity oo = (ArrayTypesEntity) o;
            return this.key.equals(oo.key) &&
                   Arrays.equals(this.f0, oo.f0) && 
                   Arrays.equals(this.f1, oo.f1) &&
                   Arrays.equals(this.f2, oo.f2) && 
                   Arrays.equals(this.f3, oo.f3) &&
                   Arrays.equals(this.f4, oo.f4) && 
                   Arrays.equals(this.f5, oo.f5) &&
                   Arrays.equals(this.f6, oo.f6) && 
                   Arrays.equals(this.f7, oo.f7) &&
                   Arrays.equals(this.f8, oo.f8) && 
                   Arrays.equals(this.f9, oo.f9);
        }
        return false;
    }
}

enum MyEnum { ONE, TWO };

@Entity
class EnumTypesEntity {

    @PrimaryKey
    ComplexKey key;

    private Thread.State f0;
    private MyEnum f1;
    private Object f2;

    EnumTypesEntity() { }
    
    EnumTypesEntity(int i) {
        key = new ComplexKey(i);
        f0 = Thread.State.RUNNABLE;
        f1 = MyEnum.ONE;
        f2 = MyEnum.TWO;
    }
    
    public ComplexKey getKey() {
        return key;
    }

    public void setKey(ComplexKey key) {
        this.key = key;
    }
    
    public Thread.State getF0() {
        return f0;
    }

    public void setF0(Thread.State f0) {
        this.f0 = f0;
    }

    public MyEnum getF1() {
        return f1;
    }

    public void setF1(MyEnum f1) {
        this.f1 = f1;
    }

    public Object getF2() {
        return f2;
    }

    public void setF2(Object f2) {
        this.f2 = f2;
    }

    public void modify() {
        f0 = Thread.State.WAITING;
        f1 = MyEnum.TWO;
        f2 = Thread.State.RUNNABLE;
    }

    @Override
    public String toString() {
        return "EnumTypesEntity: (ComplexKey: (" + key + ")," + f0 + "," +
            f1 + "," + f2 + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof EnumTypesEntity) {
            EnumTypesEntity oo = (EnumTypesEntity) o;
            return this.key.equals(oo.key) && (this.f0.equals(oo.f0)) && 
                   (this.f1.equals(oo.f1)) && (this.f2.equals(oo.f2));
        }
        return false;
    }
}

@Entity
class ProxyTypesEntity {

    @PrimaryKey
    ComplexKey key;

    private Set<Integer> f0 = new HashSet<Integer>();
    private Set<Integer> f1 = new TreeSet<Integer>();
    private Object f2 = new HashSet<Address>();
    private HashMap<String,Integer> f3 = new HashMap<String,Integer>();
    private TreeMap<String,Address> f4 = new TreeMap<String,Address>();
    private List<Integer> f5 = new ArrayList<Integer>();
    private LinkedList<Integer> f6 = new LinkedList<Integer>();

    ProxyTypesEntity(int i) {
        key = new ComplexKey(i);
        f0.add(123);
        f0.add(456);
        f1.add(456);
        f1.add(123);
        HashSet<Address> s = (HashSet) f2;
        s.add(new Address("city", "state", 11111));
        s.add(new Address("city2", "state2", 22222));
        s.add(new Address("city3", "state3", 33333));
        f3.put("one", 111);
        f3.put("two", 222);
        f3.put("three", 333);
        f4.put("one", new Address("city", "state", 11111));
        f4.put("two", new Address("city2", "state2", 22222));
        f4.put("three", new Address("city3", "state3", 33333));
        f5.add(123);
        f5.add(456);
        f6.add(123);
        f6.add(456);
    }

    ProxyTypesEntity() {}
    
    public ComplexKey getKey() {
        return key;
    }

    public void setKey(ComplexKey key) {
        this.key = key;
    }
    
    public Set<Integer> getF0() {
        return f0;
    }

    public void setF0(Set<Integer> f0) {
        this.f0 = f0;
    }

    public Set<Integer> getF1() {
        return f1;
    }

    public void setF1(Set<Integer> f1) {
        this.f1 = f1;
    }

    public Object getF2() {
        return f2;
    }

    public void setF2(Object f2) {
        this.f2 = f2;
    }

    public HashMap<String, Integer> getF3() {
        return f3;
    }

    public void setF3(HashMap<String, Integer> f3) {
        this.f3 = f3;
    }

    public TreeMap<String, Address> getF4() {
        return f4;
    }

    public void setF4(TreeMap<String, Address> f4) {
        this.f4 = f4;
    }

    public List<Integer> getF5() {
        return f5;
    }

    public void setF5(List<Integer> f5) {
        this.f5 = f5;
    }

    public LinkedList<Integer> getF6() {
        return f6;
    }

    public void setF6(LinkedList<Integer> f6) {
        this.f6 = f6;
    }

    public void modify() {
        f0.add(789);
        f1.remove(123);
        HashSet<Address> s = (HashSet) f2;
        s.remove(new Address("city", "state", 11111));

        f3.put("one", 11111);
        f3.put("two", 22222);
        f3.put("three", 33333);
        f4.put("one", new Address("city", "state", 10001));
        f4.put("two", new Address("city", "state", 20002));
        f4.put("three", new Address("city", "state", 30003));
        f5.add(789);
        f5.remove((Integer)123);
        f6.add(789);
    }
    
    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("ProxyTypes: (");
        
        sb.append("ComplexKey: (").append(key).append("),");
        
        sb.append("Set<Integer> ");
        sb.append(f0);
        sb.append(",");
        
        sb.append("Set<Integer> ");
        sb.append(f1);
        sb.append(",");
        
        sb.append("Object ");
        sb.append(f2);
        sb.append(",");
        
        sb.append("HashMap<String,Integer> ");
        sb.append(f3);
        sb.append(",");
        
        sb.append("TreeMap<String,Address> ");
        sb.append(f4);
        sb.append(",");
        
        sb.append("List<Integer> ");
        sb.append(f5);
        sb.append(",");
        
        sb.append("LinkedList<Integer> ");
        sb.append(f6);
        sb.append(")");
        
        return sb.toString();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof ProxyTypesEntity) {
            ProxyTypesEntity oo = (ProxyTypesEntity) o;
            return this.key.equals(oo.key) && (this.f0.equals(oo.f0)) && 
                   (this.f1.equals(oo.f1)) && (this.f2.equals(oo.f2)) && 
                   (this.f3.equals(oo.f3)) && (this.f4.equals(oo.f4)) && 
                   (this.f5.equals(oo.f5)) && (this.f6.equals(oo.f6));
        }
        return false;
    }
}

@Persistent
class SubclassEntity extends BasicEntity {

    private String one;
    private boolean two;

    public String getSubclasOne() {
        return one;
    }
    
    SubclassEntity() {}
    
    SubclassEntity(int i) {
       super(i);
       one = "one";
       two = false;
    }
    
    public ComplexKey getKey() {
        return key;
    }

    public void setKey(ComplexKey key) {
        this.key = key;
    }

    public void setSubclassOne(String one) {
        this.one = one;
    }

    public boolean isSubclassTwo() {
        return two;
    }

    public void setSubclassTwo(boolean two) {
        this.two = two;
    }

    public void modify() {
        super.modify();
        one += "-subclass";
        two = true;
    }
    
    @Override
    public String toString() {
        return "Subclass: (" + super.toString() + "," + one + "," + two + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof SubclassEntity) {
            SubclassEntity oo = (SubclassEntity) o;
            return (this.one.equals(oo.one)) && (this.two == oo.two);
        }
        return false;
    }
}

@Persistent
class Address {

    private String city;
    private String state;
    private int zip;
    
    Address() {}

    Address(String city, String state, int zip) {
        this.city = city;
        this.state = state;
        this.zip = zip;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public int getZip() {
        return zip;
    }
    
    @Override
    public String toString() {
        return "Address: (" + city + "," + state + "," + zip + ")";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof Address) {
            Address oo = (Address) o;
            return (this.city.equals(oo.city)) && 
                   (this.state.equals(oo.state)) &&
                   (this.zip == oo.zip);
        }
        return false;
    }
}

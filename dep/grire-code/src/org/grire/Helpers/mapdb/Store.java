package org.grire.Helpers.mapdb;

import java.io.IOError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.Adler32;

/**
 * Low level record store.
 */
public abstract class Store implements Engine{

    protected final boolean checksum;
    protected final boolean compress;
    protected final boolean encrypt;
    protected final byte[] password;
    protected final EncryptionXTEA encryptionXTEA;

    /** default serializer used for persistence. Handles POJO and other stuff which requires write-able access to Engine */
    protected SerializerPojo serializerPojo;


    protected final ThreadLocal<CompressLZF> LZF;

    protected Store(boolean checksum, boolean compress, byte[] password) {


        this.checksum = checksum;
        this.compress = compress;
        this.encrypt =  password!=null;
        this.password = password;
        this.encryptionXTEA = !encrypt?null:new EncryptionXTEA(password);

        this.LZF = !compress?null:new ThreadLocal<CompressLZF>() {
            @Override
            protected CompressLZF initialValue() {
                return new CompressLZF();
            }
        };
    }

    public abstract long getMaxRecid();
    public abstract ByteBuffer getRaw(long recid);
    public abstract Iterator<Long> getFreeRecids();
    public abstract void updateRaw(long recid, ByteBuffer data);

    /** returns maximal store size or `0` if there is no limit */
    public abstract long getSizeLimit();

    /** returns current size occupied by physical store (does not include index). It means file allocated by physical file */
    public abstract long getCurrSize();

    /** returns free size in  physical store (does not include index). */
    public abstract long getFreeSize();

    /** get some statistics about store. This may require traversing entire store, so it can take some time.*/
    public abstract String calculateStatistics();

    public void printStatistics(){
        System.out.println(calculateStatistics());
    }

    /**
     * @return default serializer used in this DB, it handles POJO and other stuff.
     */
    public  SerializerPojo getSerializerPojo() {
        if(serializerPojo==null){
            //TODO thread safe lazy init
            final CopyOnWriteArrayList<SerializerPojo.ClassInfo> classInfos = get(Engine.CLASS_INFO_RECID, SerializerPojo.serializer);
            serializerPojo = new SerializerPojo(classInfos);
        }
        return serializerPojo;
    }


    protected final ReentrantLock structuralLock = new ReentrantLock();
    protected final ReentrantReadWriteLock[] locks = Utils.newReadWriteLocks();


    protected void lockAllWrite() {
        for(ReentrantReadWriteLock l:locks)l.writeLock().lock();
        structuralLock.lock();
    }

    protected void unlockAllWrite() {
        structuralLock.unlock();
        for(ReentrantReadWriteLock l:locks)l.writeLock().unlock();
    }



    protected final Queue<DataOutput2> recycledDataOuts = new ArrayBlockingQueue<DataOutput2>(128);


    protected <A> DataOutput2 serialize(A value, Serializer<A> serializer){
        try {
            DataOutput2 out = newDataOut2();

            serializer.serialize(out,value);

            if(out.pos>0){

                if(compress){
                    DataOutput2 tmp = newDataOut2();
                    tmp.ensureAvail(out.pos+40);
                    final CompressLZF lzf = LZF.get();
                    int newLen;
                    try{
                        newLen = lzf.compress(out.buf,out.pos,tmp.buf,0);
                    }catch(IndexOutOfBoundsException e){
                        newLen=0; //larger after compression
                    }
                    if(newLen>=out.pos) newLen= 0; //larger after compression

                    if(newLen==0){
                        recycledDataOuts.offer(tmp);
                        //compression had no effect, so just write zero at beginning and move array by 1
                        out.ensureAvail(out.pos+1);
                        System.arraycopy(out.buf,0,out.buf,1,out.pos);
                        out.pos+=1;
                        out.buf[0] = 0;
                    }else{
                        //compression had effect, so write decompressed size and compressed array
                        final int decompSize = out.pos;
                        out.pos=0;
                        Utils.packInt(out,decompSize);
                        out.write(tmp.buf,0,decompSize);
                        recycledDataOuts.offer(tmp);
                    }

                }


                if(encrypt){
                    int size = out.pos;
                    //round size to 16
                    if(size%EncryptionXTEA.ALIGN!=0)
                        size += EncryptionXTEA.ALIGN - size%EncryptionXTEA.ALIGN;
                    final int sizeDif=size-out.pos;
                    //encrypt
                    out.ensureAvail(sizeDif+1);
                    encryptionXTEA.encrypt(out.buf,0,size);
                    //and write diff from 16
                    out.pos = size;
                    out.writeByte(sizeDif);
                }

                if(checksum){
                    Adler32 crc = new Adler32();
                    crc.update(out.buf,0,out.pos);
                    out.writeInt((int)crc.getValue());
                }

                if(CC.PARANOID)try{
                    //check that array is the same after deserialization
                    DataInput2 inp = new DataInput2(Arrays.copyOf(out.buf,out.pos));
                    byte[] decompress = deserialize(Serializer.BYTE_ARRAY_NOSIZE,out.pos,inp);

                    DataOutput2 expected = newDataOut2();
                    serializer.serialize(expected,value);

                    byte[] expected2 = Arrays.copyOf(expected.buf, expected.pos);
                    //check arrays equals
                    if(!Arrays.equals(expected2,decompress)){
                        throw new InternalError();
                    }

                }catch(Exception e){
                    throw new RuntimeException(e);
                }
            }
            return out;
        } catch (IOException e) {
            throw new IOError(e);
        }

    }

    protected DataOutput2 newDataOut2() {
        DataOutput2 tmp = recycledDataOuts.poll();
        if(tmp==null) tmp = new DataOutput2();
        else tmp.pos=0;
        return tmp;
    }


    protected <A> A deserialize(Serializer<A> serializer, int size, DataInput2 di) throws IOException {
        if(size>0){
            if(checksum){
                //last two digits is checksum
                size -= 4;

                //read data into tmp buffer
                DataOutput2 tmp = newDataOut2();
                tmp.ensureAvail(size);
                int oldPos = di.pos;
                di.read(tmp.buf,0,size);
                di.pos = oldPos;
                //calculate checksums
                Adler32 adler = new Adler32();
                adler.update(tmp.buf,0,size);
                recycledDataOuts.offer(tmp);
                int check = (int) adler.getValue();
                int checkExpected = di.buf.getInt(di.pos+size);
                if(check!=checkExpected)
                    throw new IOException("Checksum does not match, data broken");
            }

            if(encrypt){
                DataOutput2 tmp = newDataOut2();
                size-=1;
                tmp.ensureAvail(size);
                di.read(tmp.buf,0,size);
                encryptionXTEA.decrypt(tmp.buf, 0, size);
                int cut = di.readUnsignedByte(); //length dif from 16bytes
                di = new DataInput2(tmp.buf);
                size -= cut;
            }

            if(compress) {
                final int origPos = di.pos;
                int decompSize = Utils.unpackInt(di);
                if(decompSize==0){
                    size-=1;
                    //rest of `di` is uncompressed data
                }else{
                    DataOutput2 out = newDataOut2();
                    out.ensureAvail(decompSize);
                    CompressLZF lzf = LZF.get();
                    //TODO copy to heap if Volume is not mapped
                    lzf.expand(di.buf,di.pos,size-(di.pos-origPos),out.buf,0,decompSize);
                    di = new DataInput2(out.buf);
                    size = decompSize;
                }
            }

        }

        int start = di.pos;

        A ret = serializer.deserialize(di,size);
        if(size+start>di.pos)
            throw new InternalError("data were not fully read, check your serializer ");
        if(size+start<di.pos)
            throw new InternalError("data were read beyond record size, check your serializer");
        return ret;
    }



}

import java.io.*;
import java.util.*;

public class Packet implements Serializable{
    private int version;
    private byte[] content;
    private String path;//here path is relative
    private int nChunks;
    private boolean exist;
    private long residue;
    private int ithChunk;
    private String absPath;
    private static final int aHashCode = 62803;
    public Packet(byte[] content, int version,String path,boolean exist){
        this.version = version;
        this.content = content;
        this.path = path;
        this.exist = exist;
        this.nChunks = 1;
        this.ithChunk = 0;
    }
    public void setAbsPath(String p){
        this.absPath = p;
    }
    public String getAbsPath(){
        return absPath;
    }



    public boolean exists(){
        return exist;
    }
    public int getVersion(){
        return version;
    }
    public byte[] getByte(){
        return content;
    }
    public String getPath(){
        return path;
    }
    public synchronized void setIthChunk(int i){
        ithChunk = i;
    }
    public int getIthChunk(){
        return ithChunk;
    }


    
    public synchronized void setChunks(int number){
        nChunks = number;
    }
    public int getNumberOfChunks(){
        return nChunks;
    }
    public synchronized void setResidue(long value){
        residue = value;
    }
    public long getResidue(){
        return residue;
    }
    @Override
    public int hashCode(){
    
        return path.hashCode()+aHashCode;
    }

    @Override
    public boolean equals(Object obj){
        if(!(obj instanceof Packet)){
            return false;
        }
        String targetName = ((Packet) obj).getPath();
        Integer targetVersion = ((Packet) obj).getVersion();
        byte[] targetByte = ((Packet) obj).getByte();

        boolean sameName = targetName.equals(path);
        boolean sameVersion = targetVersion.equals(version);
        boolean sameByte = Arrays.equals(targetByte,content);
        return (sameName && sameVersion && sameByte);
        

    }







}



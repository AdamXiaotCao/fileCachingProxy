
import java.io.*;
import java.util.*;

public class CacheFile {
    private String path;
    private boolean inUse;
    private static final int aHashCode = 128765;
    public CacheFile(String path, boolean inUse){
        this.path = path;//here the path is full path
        this.inUse = inUse;
    }
    public boolean isUsing(){
        return inUse;
    }
    public String getName(){
        return path;
    }
    public synchronized void use(){
        inUse = true;
    }
    public synchronized void close(){
        inUse = false;
    }

    @Override
    public int hashCode(){
        return path.hashCode()+aHashCode;
    }

    @Override
    public boolean equals(Object obj){
        if(!(obj instanceof CacheFile)){
            return false;
        }
        String targetName = ((CacheFile) obj).getName();
        boolean targetInUse = ((CacheFile) obj).isUsing();

        boolean sameName = targetName.equals(path);
        boolean sameUse = (inUse == targetInUse);
        return (sameName && sameUse);
        

    }
}


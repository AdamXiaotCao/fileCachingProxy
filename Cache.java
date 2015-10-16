import java.util.*;
import java.nio.file.*;
import java.nio.*;
import java.util.concurrent.*;
import java.io.*;
//questions: concureency? block on same file, allows for different file?
//update LRU?
//change into chunks  -> modify Packet class
//

public class Cache{
    private int size;
    private int currentSize;
    private LinkedList<CacheFile> LRU; 
    private String rootDir;
    private ConcurrentHashMap<String, Integer> localVersionMap;
    private ConcurrentHashMap<String, Object> lockMap;
    private Object superLock;
    public Cache(int size,String rootDir){
        this.size = size;
        currentSize = 0;
        this.rootDir = rootDir;
        LRU = new LinkedList<CacheFile>();
        localVersionMap = new ConcurrentHashMap<String,Integer>();
        lockMap = new ConcurrentHashMap<String, Object>();
        superLock = new Object();

    }
    //check if currentSize + size exceeds cacheSize
    public boolean checkSize(int additionalSize){
        System.err.println("Cache: checking size, current is " + currentSize);
        System.err.println("Cache: checking size, additionaled is " + additionalSize);
        return ((currentSize + additionalSize) <= this.size);
    }
  
    //kept evicting data from cache until we have 
    //enought space for the data that has given size;
    public synchronized boolean evict(int sizeNeeded){
        System.err.println("Cache: trying to evict files sizeNeeded" + sizeNeeded);
        if( sizeNeeded > size){
            System.err.println("Cache: in evict, size is too big, can't");
            //simply can't cache it
            return false;
        }
        //we can clean up versions right here!!TODO
        int i = 0;
        while (i < LRU.size()){
            CacheFile cf = LRU.get(i);
            if(!cf.isUsing()){
                removeFile(cf.getName());
                i--;
            }
            if(checkSize(sizeNeeded)){
                System.err.println("Cache: evcit produce enough space for size " + sizeNeeded);
                return true;
            }
            i++;
        }
        System.err.println("Cache: after evict, still no enough space");
        //still does not have enough space after evicting files
        return false;
    }
    public synchronized void consumeSpace (int size){
        currentSize += size;
        System.err.println("Cache: consumed Space with size " + size + " now is " + currentSize);
    }
    public synchronized void freeSpace (int size){
        currentSize -= size;
        System.err.println("Cache: freed Space with size " + size + " now is " + currentSize);
    }



    /*
     * writeToDisk writes a file to disk and increase currentSize
     *
     */
    public synchronized void writeToDisk(Packet p){
        String path = p.getPath();
        String fullPath = rootDir + p.getPath();
        System.err.println("Cache: writing files to disk with path " + fullPath);
        Object fileLock = lockMap.get(fullPath);
        synchronized(superLock){ 
            if (fileLock == null){
                fileLock = new Object();
                lockMap.put(fullPath,fileLock);
            }
        }
        int fSize = p.getByte().length;
        if(!checkSize(fSize)){
            System.err.println("Cahce: in writeToDisk, not enough space");
        }
        synchronized(fileLock){
            //assume file does not exist
            File target = new File(fullPath);
            String relative = new File(rootDir).toURI().relativize(target.toURI()).getPath();
            File relativeF = new File(relative);
            System.err.println("Cache: relative is " + relative );
            File parent = relativeF.getParentFile();
            String fName = target.getName();
            String suffix = "";
            if (parent != null){
                suffix += parent.getPath();
                suffix = suffix.replace("/",".");
                System.err.println("Cache; in write to disk suffix is " + suffix);
            }
            File fixedF = new File(rootDir+fName+suffix);

            //here path is full path
            int oldSize=0;
            try{
                
                //maybe I should update size here
                if (fixedF.exists()){
                    oldSize = (int) fixedF.length();
                    //delete the old version
                    boolean deleteResult = fixedF.delete();
                }

                boolean createResult;
                if(!fixedF.exists()){
                    createResult = fixedF.createNewFile();
                    if(!createResult){
                        System.err.println("Cache: failed to create a new file to disk!");
                    }

                }

            }catch(IOException e){
                e.printStackTrace();
                return;
            }
            try{
                
                 
                RandomAccessFile ras = new RandomAccessFile(fixedF,"rw");
                ras.write(p.getByte());
                ras.close();
                localVersionMap.put(p.getPath(),p.getVersion());
                consumeSpace(fSize-oldSize);
                return;
            }catch(FileNotFoundException e){
                e.printStackTrace();
                return;
            }catch(IOException e){
                e.printStackTrace();
                return;
            }
        }
    }
    public Integer getVersion(String path){
        return localVersionMap.get(path);
    }
    /**
     * removeFile method takes path and deletes it from the disk
     *
     */
    public boolean removeFile(String path){
        Object fileLock = lockMap.get(path);
        if (fileLock == null){
            System.err.println("Cache: oh no remove with null filelock");
            return false;
        }
        synchronized(fileLock){
            File f = new File(path);
            String relative = new File(rootDir).toURI().relativize(f.toURI()).getPath();
            File relativeF = new File(relative);
            System.err.println("Cache: relative is " + relative );
            File parent = relativeF.getParentFile();
            String fName = f.getName();
            String suffix = "" ;

            if (parent != null){
                suffix += parent.getPath();
                suffix = suffix.replace("/",".");
                System.err.println("Cache; in remove suffix is " + suffix);
            }
            File fixedF = new File(rootDir+fName+suffix);
            if (!fixedF.exists()){
                //file does not exist
                System.err.println("Cache: in removeFile, trying to delete a file does not exist return false");
                return false;
            }

            freeSpace((int) fixedF.length());
            fixedF.delete();
            //remove this file from local version map
            localVersionMap.remove(path);
            int index = getCacheFileIndex(path);
            System.err.println("Cache: removing file " + path);
            if (index == -1){
                System.err.println("Cache: in remove index is -1");
                return false;
            }
            LRU.remove(index);
            return true;
        }
    }

    /**
     * cleanUpLocalVersions is used when evicting a lot of files and we need to
     * clean up localVersionMap as well
     */ 
    public synchronized void cleanUpLocalVersions(ArrayList<CacheFile> fileNames){
        for (int i = 0; i < fileNames.size(); i ++){
            localVersionMap.remove(fileNames.get(i));
        }
        return;
    }
    public void putVersion(String path, int version){
        localVersionMap.put(path,version);
    }


    /**
     * bring file to the last in the list
     *
     */
    public synchronized void refreshFile(String path, boolean inUse){
        System.err.println("Cache: refresh file with path " + path);
        int position = getCacheFileIndex (path);
        if (position == -1){
            LRU.add(new CacheFile(path,inUse));
        }else{
            CacheFile cf = LRU.remove(position);
            LRU.add(cf);
        }
    }


    /**
     * getCacheFile returns the index of a path in the LRU
     */
    public int getCacheFileIndex(String path){
        int i = 0;
        for (CacheFile cf : LRU){
            System.err.println("Cache: looping through LRU name is " + cf.getName()+ " with status "+ cf.isUsing());
            
            if (cf.getName().equals(path)){
                System.err.println("Cache: in get CacheFile Index found file " + path);
                return i;
            }
            i++;
        }
        System.err.println("Cache: in get CacheFile Index position is -1");
        return -1;
    }

    /**
     * changeCacheFileStatus updates the status of a file. 
     * true implies the file is currently opened, false implies 
     * this file is currenly closed
     */
    public synchronized void changeCacheFileStatus(String path,boolean status){
        //here path should be full path
        System.err.println("Cache: changing status of file " + path + " to " + status);
        for (CacheFile cf : LRU){
            if (cf.getName().equals(path)){
                if(status){
                    cf.use();
                }else{
                    cf.close();
                }
                return;
            }
        }
    }
    public int getCurrentSize(){
        return currentSize;
    }
    /*
     * cacheFile tries to write files to local disk, but if 
     * the cache does not have enought space, it will evict files
     * from cache then write the files to disk. On success it will
     * return true. false otherwise;
     */
    public boolean cacheFile(Packet p, CacheFile cf){
        int additionalSize = p.getByte().length;
        
        if (!checkSize(additionalSize)){
            //we don't have enough space for this file
            boolean result = evict(additionalSize);
            if (!result){
                return false;
            }
        }
        //we should have enough space now
        LRU.add(cf);
        //write to disk        
        //now we have a copy in disk
        writeToDisk(p);
        return true;
    }
}


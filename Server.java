import java.net.MalformedURLException;
import java.rmi.*;
import java.net.MalformedURLException;
import java.util.*;
import java.util.concurrent.*;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.LocateRegistry;
import java.io.*;
import java.util.concurrent.*;


//server is used for give files to proxy and update files if proxy recieve updates
//check exists
//check fileVersion
//update when write
//


public class Server extends UnicastRemoteObject implements MyServer{
    private static int port;
    private static String rootDir;
    private Object myLock;
    private Object versionLock;
    private ConcurrentHashMap<String,Integer> fileVersionMap;
    private ConcurrentHashMap<String, Object> lockMap;
    private final long intMax = Integer.MAX_VALUE;
    private static final int CHUNKSIZE = 1048576; //1MB
    public Server() throws RemoteException{
        myLock = new Object();
        fileVersionMap = new ConcurrentHashMap<String,Integer>();
        lockMap = new ConcurrentHashMap<String,Object>();
        versionLock = new Object();
    }

    //check if proxy version is outdated or not
    public boolean checkVersion(String path, int version) throws RemoteException{
        
        String myPath = path;
        int fileVersion;
        synchronized (versionLock){
            fileVersion = fileVersionMap.get(path);
        }
        if(version < fileVersion){
            return false;
        }else{
            return true;
        }
    }
    

    public Packet getFile(String path) throws RemoteException{
        //needs to refactor because of chunking
        System.err.println("Server: file requested " + path);
        String myPath = rootDir + path;
        File target = new File(myPath);
        Packet p;
        byte[] result;
        Integer byteLength, version;
        int nChunks = 0;

        RandomAccessFile ras;
        if(!target.exists()){
            //no such file
            result = new byte[0];
            p = new Packet(result,-1,"",false);
            return p;
        }else{
            try{
                ras = new RandomAccessFile(target,"r");
                result = new byte[(int)ras.length()];
                ras.readFully(result);
                // TODO read file into each bytes
                synchronized (versionLock){
                    version = fileVersionMap.get(path);
                
                    if (version == null){
                    //not in fileVersionMap, check if we actually have it
                        version = 0;
                        fileVersionMap.put(path,version);
                    }
                }

                p = new Packet(result,version,path,true);
                return p;
            }catch(FileNotFoundException e){
                System.err.println("Server: inside getFile, FileNotFoundException " + e);
                return null;
            }catch(IOException e){
                System.err.println("Server: inside getFile, IOException" + e );
                
                return null;
            }
        }
    }

    /** update file is called when a file needs to be updated
     * @param
     * @return
     */
    public boolean updateFile(Packet p) throws RemoteException{
        //maybe i should syncrhonized around file
        String path = rootDir+p.getPath();
        System.err.println("Server: file updated " + path);
        Object fileLock = lockMap.get(path);
        synchronized(myLock){
            if (fileLock == null){
                fileLock = new Object();
                lockMap.put(path,fileLock);
            }
        }

        synchronized(fileLock){
            RandomAccessFile ras;
            File target = new File(path);
            Integer newVersion;
            if (!p.exists()){
                //if the packet from proxy's exist value is false, that 
                //means unlink is called and we need to delete our file
                if(target.exists()){

                    synchronized (versionLock){
                        fileVersionMap.remove(p.getPath());
                    }
                    return target.delete();
                }else{
                    //only return false when trying to unlink but no such file
                    return false;
                }
            }
            
            try{

                synchronized (versionLock){
                    newVersion = fileVersionMap.get(p.getPath())+1;
                }
            }catch (NullPointerException e){
                newVersion = 0 ;
                //server does not have this file in disk
            }
            if(target.exists()){
                target.delete();
            }else{
                File parent = target.getParentFile();
                if (!parent.exists()){
                    parent.mkdirs();
                }

            }

            try {
                target.createNewFile();
            }catch (IOException e){
                System.err.println("Server: trying to create file but got err");
            
                e.printStackTrace();
            }
            if(p.getByte().length==0){
                //just create an empty file
                //does not need to write anything to it
            }else{
                try{
                    ras = new RandomAccessFile(target,"rw");
                    //ras.seek(0); //points at the beginning of the file
                    ras.write(p.getByte());
                    ras.close();
                }catch ( FileNotFoundException e){
                    e.printStackTrace();
                }catch (IOException e){
                    e.printStackTrace();
                }
            }

            synchronized (versionLock){
                fileVersionMap.put(p.getPath(), newVersion);
            }
            return true;
        }
    } 
    public static void main(String[] args){
        port = Integer.parseInt(args[0]);
        rootDir = args[1]+"/";
        try{
            LocateRegistry.createRegistry(port);
        }catch (RemoteException e){
            e.printStackTrace();


        }
        Server server = null;
        try{

            server = new Server();
        }catch (RemoteException e){
            System.err.println("Server: trying to create server instantce");

        }
        try{
            Naming.rebind(String.format("//127.0.0.1:%d/ServerService",port),server);

        }catch (RemoteException e){
            System.err.println("Server: trying to bind server failed");
        }catch (MalformedURLException e){
            System.err.println("MalformedURLException e");
        }
        System.err.println("Server is listening");
    }
}


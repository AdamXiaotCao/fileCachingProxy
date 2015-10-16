/* Sample skeleton for proxy */
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.rmi.*;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.LocateRegistry;
import java.net.MalformedURLException;

//TODO; open file at different directories.
//TODO: unlink then create.
//TODO: unlink then open.
//TODO: chunking
//TODO: slow reader, slow writer for LRU2
//TODO: create copy for read
//TODO: but if the original gets deleted, then the copy should be deleted on close
//TODO: if no one is reading it , then it should be deleted
//TODO: use relative path to create file

class Proxy {
    private static int currentSize;
    private static final int MAXFILES = 65536;
    private static int fileCount = 0;
    private static int fds = 0;
    private static Object myLock = new Object();
    private static String serverAddress;
    private static int serverPort;
    private static String rootDir;
    private static int cacheSize;
    private static MyServer server;
    private static Cache cache;
    private static final int CHUNKSIZE = 1048576; //1MB
    private static Object tableLock = new Object();
    private ConcurrentHashMap<String, Object> lockMap = new ConcurrentHashMap<String, Object>();
    private static ConcurrentHashMap<String, ArrayList<String>> dupMap = new ConcurrentHashMap<String,ArrayList<String>> ();

	private static class FileHandler implements FileHandling {

        private ConcurrentHashMap<Integer, MyFile> fdMap = new ConcurrentHashMap<Integer, MyFile>();
        public int open(String path, OpenOption o) {
            System.err.println("Proxy: open with path:" + path);
            //check if file exist locally, if not ask for serverver
            //if exist check server about version. if outdataed, get newest version
            String fullPath = rootDir+path;
            //if it is open for write, create a copy of a file then store it
            if (server == null){
                System.err.println("Proxy: server is not initialized");
            }
            if (fileCount > MAXFILES){
                System.err.println("in open before return EMFILE");
                return Errors.EMFILE;
            }
            File target = new File(fullPath);
            //check if fullPath points inside rootDir
            boolean inDir = checkFileLocation(target);
            if (!inDir){
                System.err.println("Proxy: trying to access file that is outside directory");
                return Errors.EPERM;
            }
            Packet p = null;
            RandomAccessFile ras;
            String mode = "";
            int fileSize = 0;
            switch(o){
                case READ:
                    mode = "r";
                    if (!target.exists()){
                        System.err.println("file" + path + "does not exist");
                        //we don't have file locally, need to get from server
                        p = Proxy.getFile(path);
                        fileSize = p.getByte().length;
                        if (!p.exists()){
                            return Errors.ENOENT;
                        }else{
                            //successfully get file from Server
                            //check size
                            boolean cacheResult = cache.cacheFile(p, new CacheFile(fullPath,true));
                            if(!cacheResult){
                                cache.changeCacheFileStatus(fullPath,false);
                                return Errors.ENOMEM;
                            }
                        }
                    }else{
                        System.err.println("file" + path + "exist");
                        //we have a local copy
                        Integer version = cache.getVersion(path);
                        cache.changeCacheFileStatus(fullPath,true);
                        Proxy.renewFile(path,version);//make sure we have the newest version
                        cache.refreshFile(fullPath,true);//now we open this file so make it MRU
                    }
                    break;
                case WRITE:
                    mode = "rw";
                    if (target.isDirectory()){
                        return Errors.EISDIR;
                    }
                    if (!target.exists()){
                        System.err.println("Proxy: rw file" + path + "does not exist");

                        p = Proxy.getFile(path); //pass relative path 
                        //we can make this into one single method
                        if (!p.exists()){
                            return Errors.ENOENT;
                        }else{
                            //we need to cache this file
                            boolean cacheResult = cache.cacheFile(p,new CacheFile(fullPath,true));
                            if (!cacheResult){
                                cache.changeCacheFileStatus(fullPath,false);
                                return Errors.ENOMEM;
                            }
                            //now we have a copy in disk
                        }
                    }else{
                        System.err.println("Proxy, rw file" + path + "exist");
                        //we have a local copy, check version and modify LRU
                        //we can also make this one single method
                        Integer version = cache.getVersion(path);
                        cache.changeCacheFileStatus(fullPath,true);
                        Proxy.renewFile(path,version);
                        cache.refreshFile(fullPath,true);
                    }
                    break;
                case CREATE:
                    //automatic fetch update
                    if (target.isDirectory()){
                        return Errors.EISDIR;
                    }
                    try {
                        mode = "rw";
                        //create an empty file on server get it back
                        if (!target.exists()){
                            System.err.println("Proxy: create file" + path + " does not exist");
                            p = Proxy.getFile(path); //pass relative path 
                            //check if server has this file or not
                            if (!p.exists()){
                                //server does not have it either 
                                Proxy.createServerEmptyFile(path);
                                //create an empty file on server and locally

                            }else{
                                //write to disk        
                            System.err.println("Proxy: create file" + path + " server exist");
                                boolean result = cache.cacheFile(p,new CacheFile(fullPath,true));
                                if(!result){
                                    cache.changeCacheFileStatus(fullPath,false);
                                    return Errors.ENOMEM;
                                }

                            }


                        }else{
                            System.err.println("Proxy: create file" + path + "exist");
                            //we have a local copy
                            Integer version = cache.getVersion(path);
                            Proxy.renewFile(path,version);
                            cache.changeCacheFileStatus(fullPath,true);
                            cache.refreshFile(fullPath, true);
                        }
                    }catch (SecurityException e){
                        System.err.println(e.getMessage());
                        return Errors.EPERM;
                    }
                    break;
                case CREATE_NEW:
                    //automatic fetch update
                    if (target.isDirectory()){
                        return Errors.EISDIR;
                    }
                    try {
                        boolean result = target.exists();
                        Packet packet = Proxy.getFile(path);
                        boolean serverHasFile = packet.exists();
                        //we also need to check if server has the file
                        mode = "rw";
                        if(!result && !serverHasFile){
                            System.err.println("Proxy: create new file" + path + "does not exist");
                            Proxy.createServerEmptyFile(path);
                        }else{
                            return Errors.EEXIST;
                        }
                    }catch (SecurityException e){
                        return Errors.EPERM;
                    }
                    break;
            }
            try{
                if (mode.equals("r")){
                    //read only, no need to make copy
                    ras = null;
                    File rcopy = new File (rootDir+target.getName()+"r");//a read copy
                    if(!rcopy.exists()){
                        Packet readCopy = Proxy.createCopy(target,rootDir+target.getName()+"r");
                    }else{
                        cache.changeCacheFileStatus(rootDir+target.getName()+"r",true);

                    }
                    
                    MyFile mf = new MyFile(target,ras,mode, path, "");
                    //mf.setCopyName(target.getName()+"r");
                    synchronized(myLock){
                        fds++;  
                        fileCount ++;
                        fdMap.put(fds,mf);
                    }
                    return fds;     
                }
                else{
                    //make a copy

                    //check if we will have enough space for create copies for write

                    ArrayList<String> copies = dupMap.get(path);
                    String copyID = "";
                    //need to have a unique id for copy
                    if(copies == null){
                        copyID ="0";
                        copies = new ArrayList<String>();

                    }else{
                        copyID = String.valueOf(copies.size());
                    }
                    String newPath = path+copyID;
                    System.err.println("new Path for copy is " + newPath);
                    copies.add(newPath);
                    dupMap.put(path,copies);
                    Packet copyPacket = Proxy.createCopy(target, newPath);
                    System.err.println("Proxy: going to cache the copy with path" + newPath);
                    
                    boolean result = cache.cacheFile(copyPacket, new CacheFile(rootDir + newPath, true));
                    if(!result){
                        cache.changeCacheFileStatus(fullPath,false);
                        return Errors.ENOMEM;
                    }

                    File copy = new File(rootDir + newPath);
                    String relative = new File(rootDir).toURI().relativize(copy.toURI()).getPath();
                    File relativeF = new File(relative);
                    File parent = relativeF.getParentFile();
                    String fName = copy.getName();
                    String suffix = "";
                    if (parent != null){
                        suffix += parent.getPath();

                        suffix = suffix.replace("/",".");
                        System.err.println("Proxy: in open suffix is " + suffix);
                    }
                    File copyFixed = new File(rootDir+fName+suffix);

                    
                    //File copyFixed = new File(rootDir + copy.getName());

                    ras = new RandomAccessFile(copyFixed, mode);
                    MyFile mf = new MyFile (copyFixed, ras, mode,path,copyID);
                    mf.setCopyName(newPath);
                    synchronized(myLock){
                        fds++;
                        fileCount ++;
                    
                        fdMap.put(fds,mf);
                    }
                    return fds; 
                }    
            }catch (FileNotFoundException e){
                System.err.println(e.getMessage());
                if (target.isDirectory()){
                    return Errors.EISDIR;
                }
                return Errors.ENOENT;
            }catch (SecurityException e){
                System.err.println(e.getMessage());
                return Errors.EPERM;
            }
		}

		public int close( int fd ) {
            MyFile mf;
            synchronized (myLock){
                mf = fdMap.get(fd);
            }
            String fullPath = rootDir + mf.getOriginalName();
            cache.refreshFile(fullPath, false);//bring it to MRU and change it to closed
            cache.changeCacheFileStatus(fullPath,false);
            if (mf == null){
                return Errors.EBADF;
            }

            File f = mf.getFile();
            
            RandomAccessFile targetFile = mf.getRas();
            String mode = mf.getPm();

            if (!f.exists()){
                return Errors.EBADF;
            }
            if (!mf.hasWritten()){
                //only read
                System.err.println("Proxy: file did not get to write");
                if(mf.getCopyPath()!=null){
                    cache.removeFile(rootDir+mf.getCopyPath());
                }

                synchronized (myLock){
                    fdMap.remove(fd);
                    fileCount--;
                    cache.changeCacheFileStatus(fullPath,false);
                }
                return 0;
            }
            try{
                System.err.println("Proxy: file written");
                //update server, remove local client copy
                String copyName = mf.getCopyPath();

                RandomAccessFile copyRaf = new RandomAccessFile(new File(rootDir+copyName),"rw");
                byte[] content = new byte[(int)copyRaf.length()];
                //merge it with the orginal file
                copyRaf.readFully(content);
                String path = mf.getOriginalName();
                Packet p = new Packet(content,1,path,true);
                //server knows what's should be the newest version
                Proxy.updateFile(p); 
                copyRaf.close();
                cache.removeFile(rootDir+copyName);
                synchronized(myLock){
                    fdMap.remove(fd);
                    fileCount--;
                    cache.changeCacheFileStatus(fullPath,false);
                }
                return 0;
            }catch(EOFException e){
                System.err.println("Proxy : in close eof exception is raised");   
                return Errors.EINVAL;
                
            }catch(IOException e){
                System.err.println(e.getMessage());
                System.err.println("in close an io ex raised what to do ?");
                return Errors.EINVAL;
            }

		}

		public long write( int fd, byte[] buf ) {
            System.err.println("Proxy: fd is " + fd + " for write");
            //after write, maybe we need to evcit file to save?
            int originalSize, newSize;

            if (buf.length > cacheSize){
                //simply can't hold that big of a file
                return Errors.EINVAL;
            }

            MyFile mf;
            synchronized(myLock){
                mf = fdMap.get(fd);
            }
            if (mf == null){
                return Errors.EBADF;
            }
            mf.writeFile();
            File f = mf.getFile();
            originalSize = (int) f.length();


            if (f.isDirectory()){
                return Errors.EINVAL;
            }
            RandomAccessFile targetFile = mf.getRas();
            if (targetFile == null){
                String mode = mf.getPm();
                try {
                    targetFile = new RandomAccessFile(f,mode);
                
                    mf.setRas(targetFile);
                }catch (FileNotFoundException e){
                    return Errors.EINVAL;
                }
            }
            try{
                targetFile.write(buf);
                newSize = (int) f.length();
                if(!cache.checkSize(newSize-originalSize)){
                    if(!cache.evict(newSize-originalSize)){
                        cache.changeCacheFileStatus(rootDir + mf.getOriginalName(),false);
                        cache.removeFile(rootDir + mf.getCopyPath());//delete the copy
                        return Errors.EINVAL;
                    }
                }
                cache.consumeSpace(newSize-originalSize);
                //write may change file size
                return (long) buf.length;
            }catch (IOException e){
                //can I handle read only file better?
                return Errors.EBADF;
            }
		}
		public long read( int fd, byte[] buf ) {
            System.err.println("Proxy: read gets called");
            MyFile mf;
            synchronized (myLock){
                mf = fdMap.get(fd);
            }
            if (mf == null){
                return Errors.EBADF;
            }
            File f = mf.getFile();
            if (f.isDirectory()){
                return Errors.EISDIR;
            }
            if(!f.exists()){
                return Errors.EBADF;
            }
            String relative = new File(rootDir).toURI().relativize(f.toURI()).getPath();
            File relativeF = new File(relative);
            File parent = relativeF.getParentFile();
            String fName = f.getName();
            String suffix = "";
            if (parent != null){
                suffix += parent.getPath();
                suffix = suffix.replace("/",".");
                System.err.println("Proxy: in read, suffix is " + suffix);
            }
            File fixedF = new File(rootDir+fName+suffix);

            RandomAccessFile targetFile = mf.getRas();
            if (targetFile == null){
                String mode = mf.getPm();
                try {
                    targetFile = new RandomAccessFile(fixedF,mode);
                    mf.setRas(targetFile);
                }catch (FileNotFoundException e){
                    System.err.println(e.getMessage());
                    return Errors.EINVAL;
                }
            }
            // not so sure about error handling
            try{
                long nBytes = (long) targetFile.read(buf);
                if (nBytes == -1)
                    nBytes = 0;
                return nBytes;
            }catch (IOException e){
                return Errors.EINVAL;
            }
		}

		public long lseek( int fd, long pos, LseekOption o ) {
            MyFile mf;
            synchronized (myLock){
                mf = fdMap.get(fd);
            }
            if (mf == null){
                return Errors.EBADF;
            }
            RandomAccessFile ras = mf.getRas();
            File f = mf.getFile();
            if (ras==null){
                String mode = mf.getPm();

                try{
                    ras = new RandomAccessFile(f,mode);
                    mf.setRas(ras);
                }catch (FileNotFoundException e){
                    return Errors.EINVAL;
                }

            }

            switch (o){
                case FROM_CURRENT:
                    try{
                        long currentOff = ras.getFilePointer();
                        ras.seek(currentOff+pos);
                        long rv = ras.getFilePointer();
                        if (rv < 0){
                            return Errors.EINVAL;
                        }
                        return rv;
                    }catch (IOException e) {
                        System.err.println(e.getMessage());
                        return Errors.EINVAL;
                    }
                case FROM_END:
                    try{
                        long end = ras.length();
                        ras.seek(end+pos);
                        long rv = ras.getFilePointer();
                        if (rv < 0){
                            return Errors.EINVAL;
                        }
                        return rv;
                    }catch (IOException e){
                        System.err.println(e.getMessage());
                        return Errors.EINVAL;
                    }
                case FROM_START:
                    try{
                        ras.seek(pos);
                        long rv = ras.getFilePointer();
                        if (rv < 0){
                            return Errors.EINVAL;
                        }
                        return rv;
                    }catch (IOException e){
                        System.err.println(e.getMessage());
                        return Errors.EINVAL;
                    }
            }
            //check permission
			return Errors.ENOSYS;
		}

		public int unlink( String path ) {
            System.err.println("Proxy: unlink gets called");
            String fullPath = rootDir + path;
            File f = new File (fullPath);
            if (f.isDirectory()){
                return Errors.EISDIR;
            }
            try {//maybe we dont need this
                f.getCanonicalPath();
            }catch (IOException e){//and this
                return Errors.ENOTDIR;
            }
            if (path.equals("")){
                return Errors.ENOENT;
            }
            try{
                boolean inDir = checkFileLocation(f);
                if (!inDir){
                    return Errors.EPERM;
                }
                if (f.exists()){
                    //delete local one
                    cache.removeFile(fullPath);
                }
                boolean unlinkResult = Proxy.updateFile(new Packet(null,-1,path,false));
                if (unlinkResult){
                    return 0;
                }else{
                    return Errors.ENOENT;
                }
            }catch (SecurityException e){
                System.err.println(e.getMessage());
                return Errors.EPERM;
            }
		}
		public void clientdone() {
            
			return;
		}
	}
    //a wrapper method that checks a file version with server
    public static boolean checkVersion(String path, int version){
        try{
            return server.checkVersion(path,version);
        }catch (RemoteException e){
            return false;
        }
    }

    //does version checking, file updating, version updating
    public static void renewFile(String path, int version){
        boolean result = checkVersion(path,version);
        if (result == false){
            //outdated
            Packet p = Proxy.getFile(path);
            cache.writeToDisk(p);
            return;
        }else{
            //does nothing
            return;
        }
    }

    /*
     * a wrapper method that gets a file from server
     */
    public static Packet getFile(String path){
        try{
            return server.getFile(path);
        }catch (RemoteException e){
            System.err.println("Proxy: getting file got remoteException " + e);
            return null;
        }
    }


    /*
     * a wrapper method that sends a packet to server and updates
     * the file at server.
     */
    public static boolean updateFile(Packet p){
        try{
            return server.updateFile(p);
        }catch (RemoteException e){
            System.err.println("Proxy: updating file got remoteException " + e);
            return false;
        }
    }
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}


    //creates empty on server and proxy
    //calls writeToDisk to update file version and create empty file locally
    //
    public static void createServerEmptyFile(String path){
        byte[] empty = new byte[0];
        Packet p = new Packet(empty,0,path,true);
        Proxy.updateFile(p);
        cache.cacheFile(p,new CacheFile(rootDir+path, true));
        return;
    }

    //we need to create a copy for client to write
    public synchronized static Packet createCopy(File original, String newPath ){
        try{
            String relative = new File(rootDir).toURI().relativize(original.toURI()).getPath();
            File relativeF = new File(relative);
            File parent = relativeF.getParentFile();
            String fName = original.getName();
            String suffix = "";
            if (parent != null){
                suffix += parent.getPath();
                suffix = suffix.replace("/",".");
                System.err.println("Proxy: in create copy suffix is " + suffix);
            }
            File fixedF = new File(rootDir+fName+suffix);

            RandomAccessFile ras1 = new RandomAccessFile(fixedF,"rw");
            byte[] content = new byte[(int) ras1.length()];
            ras1.readFully(content);
            Packet copy = new Packet(content,1,newPath,true);
            ras1.close();
            return copy;

        }catch (IOException e){
            System.err.println("Proxy: copying file got FileNotFoundException"+e);
            return null;
        }
    }
    public static boolean checkFileLocation(File f){
        String rootWithOutSlash = rootDir.substring(0,rootDir.length()-1);
        File root = new File(rootDir);
        
        try{
            URI fUri = f.getCanonicalFile().toURI();
            URI rootURI = root.toURI();
            Path fPath = Paths.get(fUri);
            Path rootPath = Paths.get(rootURI);
            boolean isSub = fPath.startsWith(rootPath);
            return isSub;
        }catch(IOException e){
            return false;
        }

    }


    public static MyServer getServerInstance(String ip, int port){
        String url = String.format("//%s:%d/ServerService",ip,port);
        try{
            return (MyServer) Naming.lookup(url);
        }catch (MalformedURLException e){
            System.err.println("Proxy: url name is not appropiate"+e);
        }catch (RemoteException e){
            System.err.println("Proxy: Remote connection refused url:"+ url +e); 

        }catch (NotBoundException e){
            System.err.println("Proxy: Not bound" +e );
        }
        return null;
    }
	public static void main(String[] args) throws IOException {
        if (args.length < 4){
            System.err.println("no enough arugments");
            return;
        }
		System.out.println("Proxy starts");
        serverAddress = args[0];
        serverPort = Integer.parseInt(args[1]);
        rootDir = args[2]+"/";
        cacheSize = Integer.parseInt(args[3]);
        cache = new Cache(cacheSize,rootDir);
        server = getServerInstance(serverAddress,serverPort);
		(new Thread(new RPCreceiver(new FileHandlingFactory()))).start();
	}
}


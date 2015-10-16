import java.io.*;

public class MyFile {
    private File f;
    //private static final long serialVersionUID = 227L;
    private RandomAccessFile ras;
    private String pm;
    private String version;
    private byte[] rawData;
    private String path;
    private boolean written;
    private boolean inUse;
    private String copyPath;
    private String absPath;
    private static final int aHashCode = 628039;
    public MyFile (File f, RandomAccessFile ras, String pm, String path, String version){
        this.f = f;
        this.ras = ras;
        this.pm = pm;
        this.path = path;
        this.version = version;
        written = false;
        inUse = false;
        copyPath = null;
    }
    public void use(){
        inUse = true;
        return;
    }
    public void done(){
        inUse = false;
        return;
    }
    public void setAbsPath(String p){
        this.absPath = p;
        
    }
    public String getAbsPath(){
        return this.absPath;
    }



    public boolean isUsing(){
        return inUse;
    }
    public void setCopyName(String path){
        copyPath = path;
    }
    public String getCopyPath(){
        return copyPath;
    }




    //change file name man!
    public void setCopyVersion(String version){
        this.version = version;
    }
    public void writeFile(){
        written = true;
    }
    public boolean hasWritten(){
        return this.written;
    }



    //return the original path name without root dir
    public String getOriginalName(){
        return path;
    }
    public File getFile(){
        return f;
    }
    public RandomAccessFile getRas(){
        return ras;
    }
    //mode
    public String getPm(){
        return pm;
    }
    public void setRas(RandomAccessFile ras){
        this.ras = ras;
    }
    public String getCopyVersion(){
        return version;
    }
    public byte[] getData(){
        return rawData;
    }

    @Override
    public int hashCode(){
    
        return path.hashCode()+aHashCode;
    }

    @Override
    public boolean equals(Object obj){
        if(!(obj instanceof MyFile)){
            return false;
        }
        String targetName = ((MyFile) obj).getOriginalName();
        String targetVersion = ((MyFile) obj).getCopyVersion();
        return (targetName.equals(path) && targetVersion.equals(version));
        

    }








}


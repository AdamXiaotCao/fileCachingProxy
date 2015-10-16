import java.rmi.*;

public interface MyServer extends Remote{
    public Packet getFile(String path) throws RemoteException;
    public boolean updateFile(Packet file) throws RemoteException;
    public boolean checkVersion(String path, int version) throws RemoteException;
}


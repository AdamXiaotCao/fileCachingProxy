all: MyFile.class Packet.class Cache.class CacheFile.class MyServer.class Proxy.class Server.class

%.class: %.java
	javac $<

clean:
	rm -f *.class

package:
	tar cvzf ../mysolution.tgz written.pdf Makefile Cache.java CacheFile.java MyFile.java Packet.java MyServer.java Proxy.java Server.java

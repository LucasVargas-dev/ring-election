package ringelection;

import java.io.IOException;
import java.net.*;
import java.util.*;

public class RingElection {

    private static final int PORT = 6000;
    private static final int TIMEOUT = 5000; // Tempo limite de espera por uma resposta durante a eleição

    private static InetAddress localIP;
    private static InetAddress leader;
    private static List<InetAddress> servers;
    private static List<InetAddress> activeServers;
    private static int serverID;
    private static boolean electionInProgress;
    private static boolean runnedFirstHealthcheck;
    private static boolean isLeader; // Variável para controlar se o servidor é o líder

    public static void main(String[] args) {
        try {
            servers = new ArrayList<>();
            activeServers = new ArrayList<>();
            
            servers.add(InetAddress.getByName("192.168.0.190"));  // Adicione aqui os endereços IP dos servidores
            servers.add(InetAddress.getByName("192.168.0.104")); // conforme necessário
            
            localIP = getLocalIP();
            System.out.println("IP local: " + localIP.getHostAddress());
            serverID = calculateServerID(localIP);
            
            servers.remove(localIP);
            activeServers.add(localIP);

            DatagramSocket healthcheckSocket = new DatagramSocket(6001);
            healthcheckSocket.setSoTimeout(2500);
            
            DatagramSocket socket = new DatagramSocket(PORT);
            socket.setSoTimeout(TIMEOUT);

            // Conecta-se aos outros servidores
            for (InetAddress server : servers) {
                if (!server.equals(localIP)) {
                    connectToServer(socket, server);
                }
            }
            
            // Thread para checar status dos servers
            Thread healthcheckThread = new Thread(() -> {
                while (true) {
                    for (InetAddress server : servers) {
                        if (!isServerConnected(server, healthcheckSocket)) {
                            activeServers.remove(server);
                            
                            if (leader != null && server != null && calculateServerID(server) == calculateServerID(leader)) {
                                startElection();
                            }
                        } else if (!containsServer(activeServers, server)) {
                            activeServers.add(server);
                            System.out.println("Adicionado server: " + server.getHostAddress());
                        } 
                    }
                    
                    try {
                        Thread.sleep(1000);
                        
                        if (activeServers.size() == 1 && leader == null) {
                            startElection();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            });
            healthcheckThread.start();
            
            // Thread para receber mensagens
            Thread receiveThread = new Thread(() -> {
                byte[] receiveData = new byte[1024];
                while (true) {
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    try {
                        socket.receive(receivePacket);
                        String message = new String(receivePacket.getData()).trim();
                        System.out.println("Mensagem recebida: " + message);
                    } catch (IOException e) {
                        // Tratamento de falha de conexão com o líder
                        if (leader != null && leader.equals(receivePacket.getAddress())) {
                            System.out.println("Perda de conexão com o líder: " + leader.getHostAddress());
                            leader = null;
                            isLeader = false;
                            startElection();
                        }
                    }
                }
            });
            receiveThread.start();

            // Aguarda a entrada do usuário para enviar mensagens (apenas líder)
            Scanner scanner = new Scanner(System.in);
            while (true) {
                String message = scanner.nextLine();

                // Verifica se é o líder antes de enviar a mensagem
                if (isLeader) {
                    sendBroadcastMessage(socket, "message:" + message);
                } else {
                    System.out.println("Somente o líder pode enviar mensagens.");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static boolean containsServer(List<InetAddress> servs, InetAddress s) {
        return servs.stream().anyMatch(n -> n.getHostAddress().equals(s.getHostAddress()));
    }

    private static InetAddress getLocalIP() throws SocketException {
        Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface networkInterface = networkInterfaces.nextElement();
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    for (InetAddress server : servers) {
                        if (server != null && server.getHostAddress().equals(address.getHostAddress())) {
                            return address;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static void connectToServer(DatagramSocket socket, InetAddress server) throws IOException {
        byte[] sendData = "connect".getBytes();
        byte[] receiveData = new byte[1024];
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, server, PORT);
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

        socket.send(sendPacket);
        try {
            socket.receive(receivePacket);
            System.out.println("Conectado ao servidor: " + server.getHostAddress());
        } catch (SocketTimeoutException e) {
            
        }
    }

    private static boolean isServerConnected(InetAddress server, DatagramSocket socket) {
       byte[] sendData = "healthcheck".getBytes();
        byte[] receiveData = new byte[1024];
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, server, 6001);
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

        try {
            socket.send(sendPacket);
            socket.receive(receivePacket);
        } catch (IOException e ) {
            return false;
        }
        
        return true;
    }
    
    private static void updateIsLeader() {
        isLeader = calculateServerID(leader) == calculateServerID(localIP);
    }
    
    private static void startElection() {
        System.out.println("Iniciando eleição...");
        electionInProgress = true;
        
        Collections.sort(activeServers, (a, b) ->  String.valueOf(calculateServerID(a)).compareTo(String.valueOf(calculateServerID(b))));

        leader = activeServers.get(0);
        updateIsLeader();
        
        System.out.println("Líder eleito: " + leader.getHostAddress());
        
        electionInProgress = false;
    }

    private static void sendBroadcastMessage(DatagramSocket socket, String message) {
        byte[] sendData = message.getBytes();
        for (InetAddress server : servers) {
            if (!server.equals(localIP)) {
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, server, PORT);
                try {
                    socket.send(sendPacket);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static int calculateServerID(InetAddress server) {
        String[] ipParts = server.getHostAddress().split("\\.");
        int id = 0;
        for (String part : ipParts) {
            id += Integer.parseInt(part);
        }
        return id;
    }
}

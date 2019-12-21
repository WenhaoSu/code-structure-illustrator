import java.io.*;
import java.net.*;
import java.sql.Timestamp;
import java.util.*;

public class SDFSServer {
    // port number for receiving msgs
    private static final int port = 5001;

    // port number for sending files - clients request the file from the server's port 5002
    private static final int port_file = 5002;

    // index from 1 - 10
    private int index;

    // used for calculate the quorum
    private int count_put = 0;
    private long timestamp_put = 0;
    private int count_put_commit = 0;
    private int count_get = 0;
    private long timestamp_get = 0;
    private String maxip_get = null;
    private int count_delete = 0;
    private int count_rerep = 0;

    // UDP socket programming
    private DatagramSocket udp_socket;

    // TCP socket programming
    private ServerSocket serv_sock;

    // membership list maintainer - from MP2
    private MembershipListMaintainer membershipListMaintainer;

    // a hash table mapping from SDFS file names to SDFS file objects (whole sdfs directory)
    private HashMap<String, SDFSFile> SDFSFileList;

    // a set of SDFS file names (local sdfs directory)
    private HashSet<String> localSDFSFileList;

    // mapping from sdfs file name to local file path
    private HashMap<String, String> SDFSFileMapping;

    // start the service
    private void start() {
        // start the UDP socket
        try {
            this.udp_socket = new DatagramSocket(port);
        } catch (SocketException e) {
            this.membershipListMaintainer.log("[ERROR] " + this.membershipListMaintainer.server_id_in_log() + " - Socket creation failed!");
            e.printStackTrace();
        }
        membershipListMaintainer.log("UDP socket is created.");

        // start the TCP socket
        try {
            serv_sock = new ServerSocket(port_file);
        } catch (IOException e) {
            membershipListMaintainer.log("[ERROR] " + this.membershipListMaintainer.server_id_in_log() + " - Socket creation failed!");
            e.printStackTrace();
        }
        membershipListMaintainer.log("TCP socket is created.");

        // start the membership protocol service
        System.out.println("Starting membership list maintainer..");
        MembershipListMaintainerThread maintainer = new MembershipListMaintainerThread();
        maintainer.start();
        System.out.println("Membership list maintainer is started.");

        // start threads
        MonitorThread monitor = new MonitorThread();
        ReceiverThread receiver = new ReceiverThread();
        FileServerThread fileServer = new FileServerThread();
        RereplicateThread rereplicateThread = new RereplicateThread();

        monitor.start();
        receiver.start();
        fileServer.start();
        rereplicateThread.start();

        // waiting for threads to terminate
        try {
            maintainer.join();
            monitor.join();
            receiver.join();
            fileServer.join();
            rereplicateThread.join();
        } catch (InterruptedException e) {
            membershipListMaintainer.log("[ERROR] " + membershipListMaintainer.server_id_in_log() + " - Thread execution interrupted!");
            e.printStackTrace();
        }
    }

    // end the service
    private void end() {
        membershipListMaintainer.end();

        // close the socket
        try {
            this.udp_socket.close();
            this.serv_sock.close();
        } catch (IOException e) {
            membershipListMaintainer.log("[ERROR] " + membershipListMaintainer.server_id_in_log() + " - Socket closing failed!");
            e.printStackTrace();
        }
    }

    /**
     * Constructor
     */
    public SDFSServer(MembershipListMaintainer membershipListMaintainer, int index) {
        this.membershipListMaintainer = membershipListMaintainer;
        this.index = index;
        this.SDFSFileList = new HashMap<String, SDFSFile>();
        this.localSDFSFileList = new HashSet<String>();
        this.SDFSFileMapping = new HashMap<String, String>();
    }

    /**
     * Helper functions
     */
    // hash function mapping from a string (file name) to an integer within 10 virtual machines
    private int hash(String input) {
        // start from 1, 2, ..., 9, 10
        return Math.abs(input.hashCode()) % 10 + 1;
    }

    // translate sdfs file names to local file paths
    private String sdfsToLocal(String sdfs_file_name) {
        return this.SDFSFileMapping.getOrDefault(sdfs_file_name, null);
    }

    // given the node index, find all SDFS files stored on that node
    public ArrayList<String> findSDFSFiles(int id) {
        ArrayList<String> files = new ArrayList<String>();
        for (String sdfs_file_name : this.SDFSFileList.keySet()) {
            if (this.SDFSFileList.get(sdfs_file_name).getReplicas().contains(id)) {
                files.add(sdfs_file_name);
            }
        }
        return files;
    }

    // given current replicas, find the next active replica
    private int findNextReplica(HashSet<Integer> replicas) {
        int index = 0;
        int curr_index = Collections.max(replicas) + 1;
        while (index == 0) {
            if (curr_index == 11) {
                curr_index = 1;
            }
            if (!replicas.contains(curr_index) && this.membershipListMaintainer.getMembership_list().get(curr_index - 1).getStatus() == 1) {
                index = curr_index;
            }
            curr_index += 1;
        }
        return index;
    }

    // find initial replicas - assume that there are more than 4 alive nodes in the group (otherwise infinite loop)
    private HashSet<Integer> findInitialReplicas(String sdfs_filename) {
        HashSet<Integer> replicas = new HashSet<Integer>();
        int count = 4;
        int index = this.hash(sdfs_filename);
        while (count > 0) {
            if (this.membershipListMaintainer.getMembership_list().get(index - 1).getStatus() == 1) {
                replicas.add(index);
                count -= 1;
            }
            index += 1;
            if (index > 10) {
                index -= 10;
            }
        }
        return replicas;
    }

    // send via UDP - return 0 if no error when sending, -1 otherwise
    private int send_msg(DatagramSocket socket, String msg, String dest_ip, String message_type) {
        byte[] buf = msg.getBytes();
        DatagramPacket packet;
        try {
            packet = new DatagramPacket(buf, buf.length, InetAddress.getByName(dest_ip), SDFSServer.port);
        } catch (UnknownHostException e) {
            membershipListMaintainer.log("[ERROR] " + this.membershipListMaintainer.server_id_in_log() + " - error creating UDP packet - unknown host [" + dest_ip + "]");
            e.printStackTrace();
            return -1;
        }
        try {
            socket.send(packet);
        } catch (IOException e) {
            membershipListMaintainer.log("[ERROR] " + this.membershipListMaintainer.server_id_in_log() + " - error sending UDP packet [" + dest_ip + "]");
            e.printStackTrace();
        }
        membershipListMaintainer.log("[MESSAGE] " + this.membershipListMaintainer.server_id_in_log() + " - message sent to " + dest_ip + ":" + SDFSServer.port + " - " + message_type);
        return 0;
    }

    // send request for files via TCP
    private void send_request(String ip, String sdfs_filename, String local_path) {
        Socket clientSocket = null;
        PrintWriter out = null;
        BufferedReader in = null;

        try {
            clientSocket = new Socket(ip, port_file);
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        } catch (IOException e) {
            membershipListMaintainer.log("[ERROR] " + this.membershipListMaintainer.server_id_in_log() + " - Socket creation failed!");
            e.printStackTrace();
        }

        // send the name of the requested file
        assert out != null;
        out.println(sdfs_filename);

        // open the file to write
        FileWriter fw = null;
        File f = new File(local_path);
        try {
            fw = new FileWriter(f);
        } catch (IOException e) {
            membershipListMaintainer.log("[ERROR] " + this.membershipListMaintainer.server_id_in_log() + " - File Writer creation failed!");
            e.printStackTrace();
        }

        // read from socket
        String response = null;
        try {
            assert in != null;
            response = in.readLine();
        } catch (IOException e) {
            membershipListMaintainer.log("[ERROR] " + this.membershipListMaintainer.server_id_in_log() + " - Error reading from the socket!");
            e.printStackTrace();
        }
        while (response != null) {
            try {
                assert fw != null;
                fw.write(response + "\n");
                fw.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                response = in.readLine();
            } catch (IOException e) {
                membershipListMaintainer.log("[ERROR] " + this.membershipListMaintainer.server_id_in_log() + " - Error reading from the socket!");
                e.printStackTrace();
            }
        }

        // close the sockets and I/O stream handles
        try {
            in.close();
            out.close();
            clientSocket.close();
        } catch (IOException e) {
            membershipListMaintainer.log("[ERROR] " + this.membershipListMaintainer.server_id_in_log() + " - Error closing the socket!");
            e.printStackTrace();
        }

        // close the file writer
        try {
            assert fw != null;
            fw.close();
        } catch (IOException e) {
            membershipListMaintainer.log("[ERROR] " + this.membershipListMaintainer.server_id_in_log() + " - File Writer closing failed!");
            e.printStackTrace();
        }
    }

    /**
     * Operation functions
     */
    // local put
    private void local_put(String local_filename, String sdfs_filename, long timestamp, HashSet<Integer> replicas) {
        SDFSFile new_file = new SDFSFile(sdfs_filename, local_filename, replicas, timestamp);
        if (this.SDFSFileList.containsKey(sdfs_filename)) {
            this.SDFSFileList.replace(sdfs_filename, new_file);
        } else {
            this.SDFSFileList.put(sdfs_filename, new_file);
        }
        this.localSDFSFileList.add(sdfs_filename);
        this.SDFSFileMapping.put(sdfs_filename, local_filename);
    }

    // local get
    private SDFSFile local_get(String sdfs_filename) {
        return this.SDFSFileList.get(sdfs_filename);
    }

    // local delete
    private void local_delete(String sdfs_filename) {
        this.SDFSFileList.remove(sdfs_filename);
        if (localSDFSFileList.contains(sdfs_filename)) {
            this.localSDFSFileList.remove(sdfs_filename);
        }
    }

    // insert or update to the sdfs
    private void put(String local_filename, String sdfs_filename) {
        long begin = new Timestamp(System.currentTimeMillis()).getTime();
        Timestamp curr_timestamp = new Timestamp(System.currentTimeMillis());
        long put_timestamp = curr_timestamp.getTime();
        HashSet<Integer> replicas = null;

        int flag = 0; // 0 for insert | 1 for update
        if (this.SDFSFileList.containsKey(sdfs_filename)) {
            // existing file -> update
            replicas = this.SDFSFileList.get(sdfs_filename).getReplicas();
            flag = 1;
            membershipListMaintainer.log("Updating file - " + sdfs_filename + " - client side location: " + local_filename);
        } else {
            // new file -> insert
            replicas = findInitialReplicas(sdfs_filename);
            membershipListMaintainer.log("Inserting new file - " + sdfs_filename + " - client side location: " + local_filename);
        }
        System.out.println("Replicas on the SDFS are: " + replicas);

        // quorum for put operation
        int quorum = 3;

        // in case that local machine is also in the replica list
        if (replicas.contains(this.index)) {
            // need one less quorum
            quorum -= 1;
        }

        // add sdfs file name to local file path translation
        this.SDFSFileMapping.put(sdfs_filename, local_filename);

        // send put-request to put remotely
        for (Integer i : replicas) {
            if (i != this.index) {
                String msg = "put_" + this.membershipListMaintainer.getMember(this.index - 1).getIp() + "_" + sdfs_filename;
                int ret = -1;
                while (ret == -1) {
                    ret = send_msg(this.udp_socket, msg, this.membershipListMaintainer.getMembership_list().get(i - 1).getIp(), "put_request");
                }
            }
        }
        membershipListMaintainer.log("Done sending put-request to all replicas - " + sdfs_filename);

        // waiting for ACKs from other replicas
        while (count_put < quorum) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        membershipListMaintainer.log("ACKs are collected meeting quorum for put-request - " + sdfs_filename);

        // the latest timestamp for the file in the SDFS
        long timestamp = timestamp_put;

        // reset count and timestamp for put-request
        count_put = 0;
        timestamp_put = 0;

        // check whether the update is made within 1 minute
        long duration = put_timestamp - timestamp;
        if (duration <= 60000) {
            Scanner in;
            in = new Scanner(System.in);
            String reply;
            System.out.println("This file has been updated within 1 minute. Are you sure that you want to update again?\n");
            System.out.println("[Yes/No]:\n");
            reply = in.nextLine();
            while (true) {
                if (reply.equals("No") || reply.equals("no")) {
                    // cancel this operation
                    return;
                } else if (reply.equals("Yes") || reply.equals("yes")) {
                    // continue this operation
                    break;
                } else {
                    // wrong command
                    System.out.println("Wrong Command!\n\n");
                    System.out.println("This file has been updated within 1 minute. Are you sure that you want to update again?\n");
                    System.out.println("[Yes/No]:\n");
                    reply = in.nextLine();
                }
            }
        }

        // send commit-put to replicas to confirm
        // construct the message with replica information
        String replica_message = "";
        for (Integer i : replicas) {
            replica_message += i + "|";
        }
        for (Integer i : replicas) {
            if (i != this.index) {
                String msg = "commit-put_" + this.membershipListMaintainer.getMember(this.index - 1).getIp() + "_"
                        + sdfs_filename + "_" + replica_message + "_" + put_timestamp;
                int ret = -1;
                while (ret == -1) {
                    ret = send_msg(this.udp_socket, msg, this.membershipListMaintainer.getMembership_list().get(i - 1).getIp(), "commit-put");
                }
            }
        }
        membershipListMaintainer.log("Done sending commit-put to all replicas - " + sdfs_filename);

        // waiting for ACKs from other replicas
        while (count_put_commit < quorum) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        membershipListMaintainer.log("ACKs are collected meeting quorum for put-commit - " + sdfs_filename);

        // reset count
        count_put_commit = 0;

        // local put
        if (quorum == 2) {
            local_put(local_filename, sdfs_filename, put_timestamp, replicas);
        } else {
            SDFSFile new_file = new SDFSFile(sdfs_filename, null, replicas, -1);
            if (SDFSServer.this.SDFSFileList.containsKey(sdfs_filename)) {
                // existing file
                SDFSServer.this.SDFSFileList.replace(sdfs_filename, new_file);
            } else {
                // new file
                SDFSServer.this.SDFSFileList.put(sdfs_filename, new_file);
            }
        }

        // broadcast to the rest - only when inserting the file
        if (flag == 0) {
            for (int i = 0; i < membershipListMaintainer.getMembership_list().size(); i++) {
                if (!replicas.contains(i + 1) && i + 1 != this.index) {
                    String msg = "broadcast-insert_" + this.membershipListMaintainer.getMember(index - 1).getIp()
                            + "_" + sdfs_filename + "_" + replica_message;
                    send_msg(udp_socket, msg, membershipListMaintainer.getMember(i).getIp(), "broadcast-insert");
                }
            }
        }
        System.out.println("Put-request is successfully executed on the SDFS - SDFS file name: " + sdfs_filename);
        // long end = new Timestamp(System.currentTimeMillis()).getTime();
        // long seconds = (end - begin) / 1000;
        // long ms = end - begin - seconds * 1000;
        // System.out.format("Cost Time: %d seconds %d milliseconds\n", seconds, ms);
    }

    // fetch from sdfs to local
    private void get(String sdfs_filename, String local_filename) {
        long begin = new Timestamp(System.currentTimeMillis()).getTime();
        // check if it contains the file locally
        if (this.localSDFSFileList.contains(sdfs_filename)) {
            System.out.println("File is stored locally at: " + local_get(sdfs_filename).getLocalFilePath());
            return;
        } else if (this.SDFSFileList.containsKey(sdfs_filename)) {
            // send get-request to replicas
            for (Integer i : this.SDFSFileList.get(sdfs_filename).getReplicas()) {
                String msg = "get_" + this.membershipListMaintainer.getMember(this.index - 1).getIp() + "_" + sdfs_filename;
                int ret = -1;
                while (ret == -1) {
                    ret = send_msg(this.udp_socket, msg, this.membershipListMaintainer.getMembership_list().get(i - 1).getIp(), "get_request");
                }
            }
            membershipListMaintainer.log("Done sending get-requests to all replicas - " + sdfs_filename);
        } else {
            System.out.println("This file does not exit on the SDFS!");
            return;
        }

        // waiting for ACKs from other replicas
        int quorum = 3;
        while (count_get < quorum) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        membershipListMaintainer.log("ACKs are collected meeting quorum for put-request - " + sdfs_filename);

        String max_ip = maxip_get;

        // reset
        count_get = 0;
        timestamp_get = 0;
        maxip_get = null;

        // request the file and put into local dir
        System.out.println("Retrieving file " + sdfs_filename + " from " + max_ip);
        send_request(max_ip, sdfs_filename, local_filename);
        System.out.println("Get-request is successfully executed on the SDFS - SDFS file name: " + sdfs_filename);
        System.out.println("File requested is located at: " + local_filename);
        // long end = new Timestamp(System.currentTimeMillis()).getTime();
        // long seconds = (end - begin) / 1000;
        // long ms = end - begin - seconds * 1000;
        // System.out.format("Cost Time: %d seconds %d milliseconds\n", seconds, ms);
    }

    // delete from sdfs
    private void delete(String sdfs_filename) {
        HashSet<Integer> replicas = null;
        int quorum = 3;
        if (this.SDFSFileList.containsKey(sdfs_filename)) {
            // existing file -> delete
            replicas = this.SDFSFileList.get(sdfs_filename).getReplicas();
        } else {
            // file does not exist on the SDFS
            System.out.println("This file does not exist on the SDFS!");
            return;
        }

        // in case that local machine is also in the list
        if (replicas.contains(this.index)) {
            quorum = 2;
        }

        // send request to delete remotely
        for (Integer i : this.SDFSFileList.get(sdfs_filename).getReplicas()) {
            if (i != this.index) {
                String msg = "delete_" + this.membershipListMaintainer.getMember(this.index - 1).getIp() + "_" + sdfs_filename;
                int ret = -1;
                while (ret == -1) {
                    ret = send_msg(this.udp_socket, msg, this.membershipListMaintainer.getMembership_list().get(i - 1).getIp(), "delete_request");
                }
            }
        }
        membershipListMaintainer.log("Done sending delete-request to all replicas - " + sdfs_filename);

        // waiting for ACKs
        while (count_delete < quorum) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        membershipListMaintainer.log("ACKs are collected meeting the quorum for delete-request - " + sdfs_filename);

        // reset
        count_delete = 0;

        // local delete
        local_delete(sdfs_filename);

        // broadcast to the all other nodes
        for (int i = 0; i < membershipListMaintainer.getMembership_list().size(); i++) {
            if (!replicas.contains(i + 1) && i + 1 != this.index) {
                String msg = "broadcast-delete_" + this.membershipListMaintainer.getMember(index - 1).getIp()
                        + "_" + sdfs_filename;
                send_msg(udp_socket, msg, membershipListMaintainer.getMember(i).getIp(), "broadcast-delete");
            }
        }
        System.out.println("Delete-request is successfully executed on the SDFS - SDFS file name" + sdfs_filename);
    }

    // re-replicate a file to a new node with id
    private void put_rereplicate(int id, SDFSFile f) {
        // send re-replicate-request to put remotely
        boolean committed = false;

        while (!committed) {
            // construct the message with replica information
            String replica_message = "";
            for (Integer i : f.getReplicas()) {
                replica_message += i + "|";
            }
            String msg = "commit-put-rerep_" + this.membershipListMaintainer.getMember(this.index - 1).getIp() + "_"
                    + f.getSDFSFileName() + "_" + replica_message + "_" + f.getTimestamp();
            int ret = -1;
            while (ret == -1) {
                ret = send_msg(this.udp_socket, msg, this.membershipListMaintainer.getMembership_list().get(id - 1).getIp(), "rereplicate-put");
            }

            // collecting this ack
            while (count_rerep < 1) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            committed = true;
            membershipListMaintainer.log("ACK is collected for rerep-request - " + f.getSDFSFileName());

            // reset
            count_rerep = 0;
        }
        membershipListMaintainer.log("Done sending re-replicate-request to a new node - " + id + " - " + f.getSDFSFileName());
    }

    // list all the machines where the file is currently being stored
    private void listFile(String sdfs_filename) {
        System.out.println("File Name: " + sdfs_filename);
        System.out.println("Replicas who store the file: ");
        if (this.SDFSFileList.get(sdfs_filename) == null) {
            System.out.println("None");
            return;
        }
        int counter = 0;
        for (Integer i : this.SDFSFileList.get(sdfs_filename).getReplicas()) {
            System.out.println(this.membershipListMaintainer.getMembership_list().get(i - 1).getIp());
            counter += 1;
        }
        if (counter == 0) {
            System.out.println("None");
        }
    }

    // list all files which are currently being stored at this machine
    private void listStore() {
        System.out.println("All files stored on this machine:");
        if (this.localSDFSFileList.size() == 0) {
            System.out.println("None");
            return;
        }
        for (String f : this.localSDFSFileList) {
            System.out.println(f);
        }
    }

    /**
     * Membership List Maintainer thread providing membership protocol service to the SDFS
     */
    private class MembershipListMaintainerThread extends Thread {
        @Override
        public void run() {
            SDFSServer.this.membershipListMaintainer.start();
        }
    }

    /**
     * Receiver thread watching on port 5001 for receiving messages through UDP
     * Message format - message_type_message_content
     */
    private class ReceiverThread extends Thread {
        @Override
        public void run() {
            while (!SDFSServer.this.membershipListMaintainer.getShouldExit()) {
                if (SDFSServer.this.membershipListMaintainer.getStatus() != 1) {
                    continue;
                }
                // get UDP message
                byte[] data = new byte[1024];
                DatagramPacket packet = new DatagramPacket(data, data.length);
                try {
                    udp_socket.receive(packet);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                String info = new String(data, 0, packet.getLength());
                String[] messages = info.split("_");

                // if the node is idle, skip the receiver
                if (SDFSServer.this.membershipListMaintainer.getStatus() != 1) {
                    continue;
                }
                String msg_type = messages[0];
                String ip = messages[1];
                String file_name = messages[2];
                switch (msg_type) {
                    case "put": {
                        membershipListMaintainer.log("Received put request from " + ip + " - " + file_name);
                        // put-request / re-replicate-request
                        String response = "ack-put_" + membershipListMaintainer.getMember(index - 1).getIp() + "_" + file_name + "_";
                        // response contains a timestamp of file
                        if (localSDFSFileList.contains(file_name)) {
                            response += Long.toString(SDFSFileList.get(file_name).getTimestamp());
                        } else {
                            response += "0";
                        }
                        send_msg(udp_socket, response, ip, "ack to put");
                        membershipListMaintainer.log("Sent back ack-put to " + ip + " - " + file_name);
                        break;
                    }
                    case "commit-put": {
                        membershipListMaintainer.log("Received commit-put from " + ip + " - " + file_name);
                        // parse the replica-info-message
                        String[] rep = messages[3].split("\\|");
                        HashSet<Integer> replicas = new HashSet<Integer>();
                        for (String s : rep) {
                            replicas.add(Integer.parseInt(s));
                        }
                        String local_path = "";
                        if (localSDFSFileList.contains(file_name)) {
                            // update
                            local_path = SDFSFileList.get(file_name).getLocalFilePath();
                        } else {
                            // insert
                            String currentDirectory = System.getProperty("user.dir");
                            if (file_name.contains("/")) {
                                local_path = currentDirectory.substring(0, currentDirectory.length() - 7) + "sdfs_dir/"
                                        + file_name.replace("/", "-");
                            } else {
                                local_path = currentDirectory.substring(0, currentDirectory.length() - 7) + "sdfs_dir/"
                                        + file_name;
                            }
                        }
                        membershipListMaintainer.log("Local file path of sdfs file " + file_name + ": " + local_path);

                        // send request for file and put into local path - establish tcp connection with the server to get the file
                        membershipListMaintainer.log("Sending request to retrieve the file from " + ip + " - " + file_name);
                        send_request(ip, file_name, local_path);
                        membershipListMaintainer.log("File retrieved - " + file_name);

                        // put locally
                        long timestamp = Long.parseLong(messages[4]);
                        membershipListMaintainer.log("local_put with timestamp " + timestamp);
                        local_put(local_path, file_name, timestamp, replicas);

                        // send back ack
                        String response = "ack-put-commit_" + membershipListMaintainer.getMember(index - 1).getIp() + "_" + file_name;
                        send_msg(udp_socket, response, ip, "ack-put-commit");
                        membershipListMaintainer.log("Sent back ack-put-commit to " + ip + " - " + file_name);
                        break;
                    }
                    case "broadcast-insert": {
                        membershipListMaintainer.log("Received broadcast-insert from " + ip);
                        // broadcast message informing a new inserted file
                        String[] rep = messages[3].split("\\|");
                        HashSet<Integer> replicas = new HashSet<Integer>();
                        for (String s : rep) {
                            replicas.add(Integer.parseInt(s));
                        }

                        // update local sdfs file list
                        SDFSFile new_file = new SDFSFile(file_name, null, replicas, -1);
                        if (SDFSServer.this.SDFSFileList.containsKey(file_name)) {
                            // existing file
                            SDFSServer.this.SDFSFileList.replace(file_name, new_file);
                        } else {
                            // new file
                            SDFSServer.this.SDFSFileList.put(file_name, new_file);
                        }
                        break;
                    }
                    case "delete": {
                        membershipListMaintainer.log("Received delete request of " + file_name + " from " + ip);
                        // delete-request (replica)
                        local_delete(file_name);
                        membershipListMaintainer.log("Locally deleted file - " + file_name);

                        // send back ack
                        String response = "ack-delete_" + membershipListMaintainer.getMember(index - 1).getIp() + "_" + file_name;
                        send_msg(udp_socket, response, ip, "ack-delete");
                        membershipListMaintainer.log("Sent back ack-delete to " + ip);
                        break;
                    }
                    case "broadcast-delete":
                        membershipListMaintainer.log("Received broadcast-delete from " + ip);
                        // delete-request (non-replica)
                        local_delete(file_name);
                        break;
                    case "get": {
                        membershipListMaintainer.log("Received get request from " + ip);
                        // get-request
                        String response = "ack-get_" + membershipListMaintainer.getMember(index - 1).getIp() + "_"
                                + file_name + "_" + Long.toString(SDFSFileList.get(file_name).getTimestamp());
                        // response contains the timestamp of the requested file
                        send_msg(udp_socket, response, ip, "ack-get");
                        break;
                    }
                    case "commit-put-rerep": {
                        membershipListMaintainer.log("Received commit-put from " + ip + " - " + file_name);
                        // parse the replica-info-message
                        String[] rep = messages[3].split("\\|");
                        HashSet<Integer> replicas = new HashSet<Integer>();
                        for (String s : rep) {
                            replicas.add(Integer.parseInt(s));
                        }
                        String local_path = "";
                        if (localSDFSFileList.contains(file_name)) {
                            local_path = SDFSFileList.get(file_name).getLocalFilePath();
                        } else {
                            localSDFSFileList.add(file_name);
                            String currentDirectory = System.getProperty("user.dir");
                            if (file_name.contains("/")) {
                                local_path = currentDirectory.substring(0, currentDirectory.length() - 7) + "sdfs_dir/"
                                        + file_name.replace("/", "-");
                            } else {
                                local_path = currentDirectory.substring(0, currentDirectory.length() - 7) + "sdfs_dir/"
                                        + file_name;
                            }
                        }
                        membershipListMaintainer.log("Local file path for sdfs file " + file_name + ": " + local_path);

                        // send request for file and put into local path - establish tcp connection with the server to get the file
                        membershipListMaintainer.log("Sending request to retrieve the file from " + ip + " - " + file_name);
                        send_request(ip, file_name, local_path);
                        membershipListMaintainer.log("File retrieved - " + file_name);

                        long timestamp = Long.parseLong(messages[4]);
                        membershipListMaintainer.log("local_put with timestamp " + timestamp);
                        local_put(local_path, file_name, timestamp, replicas);
                        // send back ack
                        String response = "ack-rerep_" + membershipListMaintainer.getMember(index - 1).getIp() + "_" + file_name;
                        send_msg(udp_socket, response, ip, "ack-rerep");
                        break;
                    }
                    case "broadcast-update": {
                        membershipListMaintainer.log("Received broadcast-update from " + ip);
                        // broadcast message informing a newly updated file - after re-replication
                        String[] rep = messages[3].split("\\|");
                        HashSet<Integer> replicas = new HashSet<Integer>();
                        for (String s : rep) {
                            replicas.add(Integer.parseInt(s));
                        }

                        // update local sdfs file list
                        SDFSFile new_file = new SDFSFile(file_name, null, replicas, -1);
                        if (SDFSServer.this.SDFSFileList.containsKey(file_name)) {
                            // existing file
                            SDFSServer.this.SDFSFileList.get(file_name).setReplicas(replicas);
                        } else {
                            // new file
                            SDFSServer.this.SDFSFileList.put(file_name, new_file);
                        }
                        break;
                    }
                    case "ack-put":
                        // record count and max timestamp
                        membershipListMaintainer.log("Received ack-put from " + ip);
                        count_put++;
                        timestamp_put = Math.max(timestamp_put, Long.parseLong(messages[3]));
                        break;
                    case "ack-put-commit":
                        // record count
                        membershipListMaintainer.log("Received ack-put-commit from " + ip);
                        count_put_commit++;
                        break;
                    case "ack-get":
                        // record count and max timestamp with max ip
                        membershipListMaintainer.log("Received ack-get from " + ip);
                        count_get++;
                        if (timestamp_get < Long.parseLong(messages[3])) {
                            maxip_get = ip;
                            timestamp_get = Long.parseLong(messages[3]);
                        }
                        break;
                    case "ack-delete":
                        // record count
                        membershipListMaintainer.log("Received ack-get from " + ip);
                        count_delete++;
                        break;
                    case "ack-rerep":
                        // record count
                        membershipListMaintainer.log("Received ack-get from " + ip);
                        count_rerep++;
                        break;
                }
            }
        }
    }

    /**
     * FileServer thread receiving a client's request for a file and send back the file to the client
     * each client who is requesting a file need to establish TCP connections first with the server
     */
    private class FileServerThread extends Thread {
        @Override
        public void run() {
            while (!SDFSServer.this.membershipListMaintainer.getShouldExit()) {
                // establish a connection
                Socket sock = null;
                try {
                    sock = SDFSServer.this.serv_sock.accept();
                } catch (IOException e) {
                    SDFSServer.this.membershipListMaintainer.log("[ERROR] " + SDFSServer.this.membershipListMaintainer.server_id_in_log() + " - Socket accepting connection failed!");
                    e.printStackTrace();
                }

                // read input from the socket (which is the file name)
                BufferedReader in;
                String sdfs_file_name = null;
                String local_file_path = null;
                try {
                    assert sock != null;
                    in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                    sdfs_file_name = in.readLine();
                    local_file_path = sdfsToLocal(sdfs_file_name);
                } catch (IOException e) {
                    SDFSServer.this.membershipListMaintainer.log("[ERROR] " + SDFSServer.this.membershipListMaintainer.server_id_in_log() + " - Get input stream IO Exception!");
                    e.printStackTrace();
                }

                // transfer the requested file back to the client
                membershipListMaintainer.log("FTP - received request to transfer: " + sdfs_file_name);
                membershipListMaintainer.log("FTP - local file path: " + local_file_path);

                // divide the file into chunks
                assert local_file_path != null;
                DataInputStream in_f = null;
                try {
                    in_f = new DataInputStream(new FileInputStream(local_file_path));
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
                byte[] arr = new byte[1024];
                try {
                    int len = 0;
                    OutputStream os = sock.getOutputStream();
                    assert in_f != null;
                    while ((len = in_f.read(arr)) != -1) {
                        // send the 1MB of arr over socket
                        os.write(arr, 0, len);
                        os.flush();
                    }
                    sock.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Rereplicate thread receiving a client's request for a file and send back the file to the client
     * each client who is requesting a file need to establish TCP connections first with the server
     */
    private class RereplicateThread extends Thread {
        @Override
        public void run() {
            while (!SDFSServer.this.membershipListMaintainer.getShouldExit()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                // if the node is idle, skip the checker
                if (SDFSServer.this.membershipListMaintainer.getMembership_list().get(SDFSServer.this.index - 1).getStatus() != 1) {
                    continue;
                }
                // periodically check for each file, whether all four replicas are alive
                for (String f : SDFSServer.this.localSDFSFileList) {
                    if (SDFSServer.this.SDFSFileList.get(f) == null) {
                        membershipListMaintainer.log("file " + f + " does not exist in SDFSFileList but exist in localSDFSFileList");
                        continue;
                    }
                    HashSet<Integer> ids = SDFSServer.this.SDFSFileList.get(f).getReplicas();
                    ArrayList<Integer> id_list = new ArrayList<Integer>(ids);
                    Collections.sort(id_list);

                    // nodes to initiate re-replicate process on
                    ArrayList<Integer> ids_to_check = new ArrayList<Integer>();

                    // find the node who is active and has the max id among all active replicas
                    int max_active_id = 0;
                    for (int i = id_list.size() - 1; i >= 0; i--) {
                        if (max_active_id == 0 && SDFSServer.this.membershipListMaintainer.getMembership_list().get(id_list.get(i) - 1).getStatus() == 1) {
                            max_active_id = id_list.get(i);
                            continue;
                        }
                        if (SDFSServer.this.membershipListMaintainer.getMembership_list().get(id_list.get(i) - 1).getStatus() != 1) {
                            ids_to_check.add(id_list.get(i));
                        }
                    }

                    if (ids_to_check.size() == 0) {
                        continue;
                    }

                    // only the node who has the max active id is responsible for checking
                    if (max_active_id == SDFSServer.this.index) {
                        membershipListMaintainer.log("Re-replicate is started for file " + f);
                        // check status of other replicas
                        ids.removeAll(ids_to_check);
                        ArrayList<Integer> new_ids = new ArrayList<Integer>();

                        for (int id : ids_to_check) {
                            // re-replicate the file on this node to another node
                            int next_id = SDFSServer.this.findNextReplica(ids);
                            new_ids.add(next_id);
                            ids.add(next_id);
                        }

                        // in case there are less than four replicas
                        while (ids.size() < 4) {
                            int next_id = SDFSServer.this.findNextReplica(ids);
                            new_ids.add(next_id);
                            ids.add(next_id);
                        }

                        // locally change the file object
                        SDFSFile new_f = SDFSServer.this.SDFSFileList.get(f);
                        new_f.setReplicas(ids);

                        for (int id : new_ids) {
                            SDFSServer.this.put_rereplicate(id, new_f);
                            membershipListMaintainer.log("Re-replicate for " + f + " on node #" + id);
                        }
                        membershipListMaintainer.log("Re-replicate is done for file " + f);
                    } else {
                        continue;
                    }

                    // broadcast to the rest
                    String replica_message = "";
                    for (Integer i : ids) {
                        replica_message += i + "|";
                    }
                    for (int i = 0; i < membershipListMaintainer.getMembership_list().size(); i++) {
                        if (i + 1 != SDFSServer.this.index) {
                            String msg = "broadcast-update_" + SDFSServer.this.membershipListMaintainer.getMember(i).getIp()
                                    + "_" + f + "_" + replica_message;
                            send_msg(udp_socket, msg, membershipListMaintainer.getMember(i).getIp(), "broadcast-update");
                        }
                    }
                    membershipListMaintainer.log("Done: Re-replicate-update broadcast to the SDFS - " + f);
                }
            }
        }
    }

    /**
     * Monitor thread watching for user input commands
     * Commands: join, leave, list, info, exit, put, get, delete, ls, store
     */
    private class MonitorThread extends Thread {
        @Override
        public void run() {
            String command;
            Scanner in;
            in = new Scanner(System.in);
            String commandInfo = "\nEnter the command: \n" +
                    " - join: join the group\n" +
                    " - leave: leave the group\n" +
                    " - list: list all the members in the group (active and idle) and its neighbors\n" +
                    " - info: print current server id, neighbors, on-time, etc.\n" +
                    " - exit: exit the command line environment (make sure the the node's status is idle/leave)\n" +
                    " - put [local_file_name] [sdfs_file_name]: insert or update file\n" +
                    " - get [sdfs_file_name] [local_file_name]: fetch file to local\n" +
                    " - delete [sdfs_file_name]: delete a file\n" +
                    " - ls [sdfs_file_name]: list all the machines where the file is currently being stored\n" +
                    " - store: list all files which are currently being stored at this machine";
            System.out.println(commandInfo);
            command = in.nextLine();
            while (true) {
                membershipListMaintainer.log("[INFO] " + membershipListMaintainer.server_id_in_log() + " - received command: " + command);
                if (command.equals("exit")) {
                    if (membershipListMaintainer.getStatus() == 1) {
                        // status is active, unable to exit
                        System.out.println("Unable to exit because the node is still alive.");
                    } else {
                        // notify other threads to finish and then exit
                        membershipListMaintainer.setShouldExit(true);
                        membershipListMaintainer.log("[INFO] " + membershipListMaintainer.server_id_in_log() + " - exited ");
                        System.out.println("Successfully exited.");
                        break;
                    }
                } else if (command.equals("list")) {
                    // list all vms
                    membershipListMaintainer.listAllMembers();

                    // list all neighbors
                    membershipListMaintainer.listAllNeighbors();
                } else if (command.equals("info")) {
                    // print info for local server
                    membershipListMaintainer.printInfo();
                } else if (command.equals("join")) {
                    if (membershipListMaintainer.getStatus() == 1) {
                        // already in the group
                        System.out.println("Already in the group, no action to take.");
                    } else {
                        // delete all files in the directory before rejoining
                        SDFSServer.this.localSDFSFileList.clear();
                        SDFSServer.this.SDFSFileList.clear();

                        // join the group via the introducer
                        int result = membershipListMaintainer.join();
                        if (result == 1) {
                            System.out.println("Joined successfully!");
                            membershipListMaintainer.log("[INFO] " + membershipListMaintainer.server_id_in_log() + " - Joined successfully");
                        } else {
                            System.out.println("Join-request failed, please try again!");
                            membershipListMaintainer.log("[INFO] " + membershipListMaintainer.server_id_in_log() + " - failed to join");
                        }
                    }
                } else if (command.equals("leave")) {
                    if (membershipListMaintainer.getIsIntroducer()) {
                        // introducer can only fail
                        System.out.println("Unable to leave because the node is an introducer. Introducer is not supposed to leave.");
                    } else {
                        // leave the group
                        membershipListMaintainer.leave();
                        System.out.println("Left successfully!");
                    }
                } else if (command.startsWith("put")) {
                    // insert or update a file
                    if (command.split(" ").length != 3) {
                        System.out.println("Wrong number of arguments (put [local_file_name] [sdfs_file_name])!");
                    } else {
                        String local_filename = command.split(" ")[1];
                        String sdfs_filename = command.split(" ")[2];
                        File f = new File(local_filename);
                        if (!f.exists()) {
                            System.out.println("File does not exist!");
                        } else {
                            put(local_filename, sdfs_filename);
                        }
                    }
                } else if (command.startsWith("get")) {
                    // retrieve a file
                    if (command.split(" ").length != 3) {
                        System.out.println("Wrong number of arguments (get [sdfs_file_name] [local_file_name])!");
                    } else {
                        String local_filename = command.split(" ")[2];
                        String sdfs_filename = command.split(" ")[1];
                        get(sdfs_filename, local_filename);
                    }
                } else if (command.startsWith("delete")) {
                    // delete a file
                    if (command.split(" ").length != 2) {
                        System.out.println("Wrong number of arguments (delete [sdfs_file_name])!");
                    } else {
                        String sdfs_filename = command.split(" ")[1];
                        delete(sdfs_filename);
                    }
                } else if (command.startsWith("ls")) {
                    // list all machines where a file is stored on the SDFS
                    if (command.split(" ").length != 2) {
                        System.out.println("Wrong number of arguments (ls [sdfs_file_name])!");
                    } else {
                        String sdfs_filename = command.split(" ")[1];
                        listFile(sdfs_filename);
                    }
                } else if (command.equals("store")) {
                    // list all files which are currently being stored at this machine
                    listStore();
                } else {
                    System.out.println("Wrong command!");
                }

                // enter the next query command
                System.out.println(commandInfo);
                command = in.nextLine();
            }
        }
    }

    /**
     * Main function
     */
    public static void main(String[] args) {
        // server id
        int index = Integer.parseInt(args[0]);
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        String id = String.format("%02d", index) + "-" + Long.toString(timestamp.getTime());

        // vm information
        ArrayList<String> vm_list = new ArrayList<String>();
        for (int i = 1; i <= 10; i++) {
            if (i != 10) {
                vm_list.add("fa19-cs425-g46-0" + Integer.toString(i) + ".cs.illinois.edu");
            } else {
                vm_list.add("fa19-cs425-g46-" + Integer.toString(i) + ".cs.illinois.edu");
            }
        }

        // create the server service
        MembershipListMaintainer s = null;

        // mode for starting a server - 1 for bootstrap mode & 2 for normal node
        int mode = Integer.parseInt(args[1]);

        if (mode == 1) {
            // bootstrap mode
            s = new MembershipListMaintainer(String.format("%02d", index) + "-0", vm_list.get(index - 1), vm_list);
            s.bootstrap(index);
        } else if (mode == 2) {
            // normal mode - contact the introducer
            s = new MembershipListMaintainer(id, vm_list.get(index - 1), vm_list);
            int result = s.join();
            if (result == 1) {
                System.out.println("Joined successfully!");
                s.log("[INFO] " + s.server_id_in_log() + "Joined successfully");
            } else {
                System.out.println("Join-request failed, please try again!");
                s.log("[INFO] " + s.server_id_in_log() + "fail to join");
            }
        }

        // introducer indicator - 1 for being an introducer 0 for not
        int indicator = Integer.parseInt(args[2]);

        if (indicator == 0) {
            assert s != null;
            s.setIsIntroducer(false);
        } else {
            assert s != null;
            s.setIsIntroducer(true);
        }

        // set the joining timestamp
        s.setJoin_timestamp(timestamp.getTime());

        System.out.println("\nServer ID: " + index);
        System.out.println("Mode: " + mode + " (1 for bootstrap mode & 2 for normal mode)");
        System.out.println("Introducer Indicator: " + indicator + " (1 for being a introducer & 0 for not)");

        // create the SDFS server
        SDFSServer sdfsServer = new SDFSServer(s, index);

        // start the service
        System.out.println("\nStarting the server service ...");
        sdfsServer.start();

        // end the service
        sdfsServer.end();
    }
}

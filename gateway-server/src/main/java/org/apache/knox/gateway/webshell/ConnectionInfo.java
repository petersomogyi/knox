/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.knox.gateway.webshell;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.apache.commons.io.FileUtils;
import org.apache.knox.gateway.audit.api.Action;
import org.apache.knox.gateway.audit.api.ActionOutcome;
import org.apache.knox.gateway.audit.api.Auditor;
import org.apache.knox.gateway.audit.api.ResourceType;
import org.apache.knox.gateway.websockets.WebsocketLogMessages;
import org.eclipse.jetty.io.RuntimeIOException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;


/**
* data structure to store a connection session
*/
public class ConnectionInfo {

    private InputStream inputStream;
    private OutputStream outputStream;
    private Process process;
    private final String username;
    private final Auditor auditor;
    private final WebsocketLogMessages LOG;
    private final String gatewayPIDDir;
    private Long pid;

    public ConnectionInfo(String username, String gatewayPIDDir, Auditor auditor, WebsocketLogMessages LOG) {
        this.username = username;
        this.auditor = auditor;
        this.LOG = LOG;
        this.gatewayPIDDir = gatewayPIDDir;
    }

    private void saveProcessPID(Long pid){
        File file = new File(gatewayPIDDir + "/" + "webshell_" + pid.toString() + ".pid");
        try {
            FileUtils.writeStringToFile(file, pid.toString(), StandardCharsets.UTF_8);
        } catch (IOException e){
            LOG.onError("error saving PID for webshell:" + e);
        }
        auditor.audit( Action.WEBSHELL, username+':'+pid,
                ResourceType.PROCESS, ActionOutcome.SUCCESS,"Started Bash process");
    }
    @SuppressForbidden
    public void connect(){
        try {
            ProcessBuilder builder = new ProcessBuilder( "bash","-i");
            //ProcessBuilder builder = new ProcessBuilder( "sudo","-u",username,"bash","-i");
            builder.redirectErrorStream(true); // combine stderr with stdout
            process = builder.start();
            pid = getProcessID(process);
            if (pid == -1) {
                throw new RuntimeException("Error getting process id");
            }
            saveProcessPID(pid);

            inputStream = process.getInputStream();
            outputStream = process.getOutputStream();

            Runtime.getRuntime().addShutdownHook(new Thread(this::disconnect));

            outputStream.write("cd $HOME\nwhoami\n".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch(IOException | RuntimeException e) {
            LOG.onError("Error starting bash for " + username +" : "+ e.getMessage());
            disconnect();
        }
    }

    public String getUsername(){
        return this.username;
    }
    public Long getPid(){ return this.pid; }
    public InputStream getInputStream(){
        return this.inputStream;
    }
    public OutputStream getOutputStream(){
        return this.outputStream;
    }


    public void disconnect(){
        if (process != null) {
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        auditor.audit( Action.WEBSHELL, username+':'+pid,
                ResourceType.PROCESS, ActionOutcome.SUCCESS,"destroyed Bash process");
        File fileToDelete = FileUtils.getFile(gatewayPIDDir + "/" + "webshell_" + pid.toString() + ".pid");
        FileUtils.deleteQuietly(fileToDelete);

        try {
            if (inputStream != null) {inputStream.close();}
            if (outputStream != null) {outputStream.close();}
        } catch (IOException e){
            throw new RuntimeIOException(e);
        }
    }
    /*
    private static int getVersion() {
        String version = System.getProperty("java.version");
        if(version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf(".");
            if(dot != -1) { version = version.substring(0, dot); }
        } return Integer.parseInt(version);
    }*/

    private static long getProcessID(Process p)
    {
        /* todo: wont compile with java 8
        if (getVersion() >= 9){
            return p.pid();
        }*/

        long result = -1;
        try
        {
            //for windows
            if (p.getClass().getName().equals("java.lang.Win32Process") ||
                    p.getClass().getName().equals("java.lang.ProcessImpl"))
            {
                Field f = p.getClass().getDeclaredField("handle");
                f.setAccessible(true);
                long handl = f.getLong(p);
                Kernel32 kernel = Kernel32.INSTANCE;
                WinNT.HANDLE hand = new WinNT.HANDLE();
                hand.setPointer(Pointer.createConstant(handl));
                result = kernel.GetProcessId(hand);
                f.setAccessible(false);
            }
            //for unix based operating systems
            else if (p.getClass().getName().equals("java.lang.UNIXProcess"))
            {
                Field f = p.getClass().getDeclaredField("pid");
                f.setAccessible(true);
                result = f.getLong(p);
                f.setAccessible(false);
            }
        }
        catch(Exception ex)
        {
            result = -1;
        }
        return result;
    }
}

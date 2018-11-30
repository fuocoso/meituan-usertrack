package com.ibeifeng.senior.usertrack.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Created by ibf on 10/25.
 */
public class ProcessTest {
    public static void main(String[] args) {
        String command = "sh /home/hadoop/shell "+args[0];
       // String command = "C:\\WINDOWS\\system32\\cala.exe";
      //  String command = "D:\\tool\\eclipse\\eclipse.exe";
        try {
            Process process = Runtime.getRuntime().exec(command);

            int exitValue = process.waitFor(); //进程没有结束的话，会阻塞等待状态

            if (exitValue == 0) {
                System.out.println("Success!!" + exitValue);
            } else {
                System.out.println("Failure!!" + exitValue);
                InputStream is = process.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                String line = null;
                System.out.println("====Error Msg====");
                while ((line = br.readLine()) != null) {
                    System.out.println(line);
                }
                System.out.println("====Error Msg====");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

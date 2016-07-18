package javaservant.core;

import bot.SuperRobot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by Josh on 7/15/2016.
 */
public class JSThread extends Thread {
    private final static Logger LOG = Logger.getLogger(JSThread.class.getName());
    public static final String NOT_RUNNING = "Not Running";
    public static final String RUNNING = "Running";
    public static final String SUCCEEDED = "Succeeded";
    public static final String FAILED = "Failed";
    public static final String STOPPED = "Stopped";
    public static final String READY = "Ready";
    public static final String[] STATE_LIST = {NOT_RUNNING, RUNNING, SUCCEEDED, FAILED, STOPPED, READY};

    enum RUN_STATES {
        NOT_RUNNING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    File script;
    SuperRobot bot;
    public boolean running;
    List<JSThreadListener> subscribers;

    public JSThread(SuperRobot bot, File s) {
        this.bot = bot;
        script = s;
        subscribers = new ArrayList<>();
    }

    @Override
    public void run() {
        if(running || script == null)
            return;

        running = true;
        FileReader fr;
        try {
            Map<String, Integer> labelMap = new HashMap<>();
            Map<Integer, Integer> labelLineCtrMap = new HashMap<>(); //used to map current iteration of a loop
            Map <String, String> varMap = new HashMap<>();

            fr = new FileReader(script);
            BufferedReader br =  new BufferedReader(fr);

            String line;
            int lineNum = 0;

            while((line = br.readLine()) != null)
            {
                if(!running)
                {
                    LOG.info("Thread " + this + " has been set to stop running");
                    br.close();
                    return;
                }

                if (line.contains("$(")){

                    String val = line;
                    int pos = line.indexOf(':');
                    if(pos > -1){
                        val = line.substring(pos+1); //take everything after first colon
                    }
                    String [] vars = val.split("\\Q$(\\E");
                    for(int i = 0; i < vars.length; i++){
                        if(i == 0){ // first split won't ever be needed here
                            continue;
                        }
                        String key = vars[i].substring(0,vars[i].indexOf(')'));
                        String value = varMap.get(key);

                        if (value == null) {
                            //unknown variable; abort
                            br.close();
                            throw new IllegalArgumentException("Line " + lineNum + ": \"" + key + "\"" + " is not a recognized variable! Aborting script!");
                        }
                        line = line.replace("$("+key+")", value); // replace all occurrences off the bat
                    }
                }

                if (line.toLowerCase().trim().startsWith("label")){
                    String [] vals = line.split(":");
                    if (vals.length != 2) { //we should only have a label declaration and label name
                        br.close();
                        throw new IllegalArgumentException("Error on line " + lineNum + ": label name cannot include the symbol ':'. Aborting!");
                    }
                    String labelName = vals[1].trim();
                    if (labelName.contains(",")) {
                        br.close();
                        throw new IllegalArgumentException("Error on line " + lineNum + ": label name cannot include the symbol ','. Aborting!");
                    }
                    labelMap.put(labelName, lineNum);
                } else if (line.toLowerCase().startsWith("var")){
                    int firstColon = line.indexOf(':');
                    line = line.substring(firstColon+1).trim();
                    int firstEqual = line.indexOf('=');
                    String key = line.substring(0, firstEqual).trim();
                    String val = line.substring(firstEqual+1);
                    varMap.put(key, val);
                } else if (line.toLowerCase().trim().startsWith("jumpto")){
                    String [] vals = line.split(":");
                    String[] labelVars = vals[1].trim().split(",");
                    String label = labelVars[0].trim();
                    int labelCtr = -1;
                    if(labelVars.length > 1) {
                        labelCtr = Integer.parseInt(labelVars[1]);
                    }

                    int labelLine = labelMap.get(label);
                    if(labelCtr > -1) { // this is a counted (aka non infinite running loop)
                        if(labelLineCtrMap.containsKey(lineNum)) { // then this is not the first time reaching this jumpto
                            int remainingIterations = labelLineCtrMap.get(lineNum);
                            //System.out.println("Reached back on loop line " + lineNum +  ", with " + remainingIterations + " loop(s) remaining.");
                            if(remainingIterations == 0) { //then we have just completed our last loop
                                //reset ctr by removing mapping in case this is an internal loop
                                labelLineCtrMap.remove(lineNum);
                                //System.out.println("Removed loop mapping for line " + lineNum);

                                lineNum++;
                                continue; //move to next line then get out of counted loop
                            }
                            remainingIterations--;
                            labelLineCtrMap.put(lineNum, remainingIterations);
                        } else {
                            //System.out.println("New mapping! Request to loop on line " + lineNum +  ", " + labelCtr + " times.");
                            labelLineCtrMap.put(lineNum, labelCtr-1);
                        }
                    }

                    br.close();
                    fr = new FileReader(script);
                    br =  new BufferedReader(fr);

                    for(int i = 0; i < labelLine+1;i++) //go 1 line after the label
                    {
                        br.readLine();
                    }

                    //update lineNum
                    lineNum = labelLine + 1;
                    continue; // start reading from new position
                }

                if("END".equals(line)){
                    break;
                }

                //execute each line
                bot.execute(line);
                lineNum++;
            }
            br.close();
            broadcastState(SUCCEEDED, null);
        }
        catch(FileNotFoundException | IllegalArgumentException fnfe){
            fnfe.printStackTrace();
            LOG.log(Level.SEVERE, fnfe.getMessage());
            broadcastState(FAILED, fnfe.getMessage());
        } catch(Exception e) {
            LOG.log(Level.SEVERE, "Fatal Error, due to: " + e.getMessage());
            e.printStackTrace();
            broadcastState(FAILED, e.getMessage());
        }
        finally {
            running = false;
            LOG.info("Thread " + this + " has finished running");
        }
    }

    private void broadcastState(String newState, String message) {
        for (JSThreadListener subscriber : subscribers) {
            subscriber.onJsThreadStateChange(newState, message);
        }
    }

    public void addSubscriber(JSThreadListener subscriber) {
        subscribers.add(subscriber);
    }

}
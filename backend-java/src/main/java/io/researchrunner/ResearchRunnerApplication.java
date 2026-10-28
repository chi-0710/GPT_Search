package io.researchrunner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ResearchRunnerApplication {
    public static void main(String[] args) {
        if(args.length>0 && args[0].equals("research")) {
            System.exit(ResearchCli.run(java.util.Arrays.copyOfRange(args,1,args.length)));return;
        }
        SpringApplication.run(ResearchRunnerApplication.class, args);
    }
}

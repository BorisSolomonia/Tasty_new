package ge.tastyerp.waybill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "ge.tastyerp")
public class WaybillServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaybillServiceApplication.class, args);
    }
}

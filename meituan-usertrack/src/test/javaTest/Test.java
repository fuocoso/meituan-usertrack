import java.util.Arrays;
import java.util.Calendar;
import java.util.Random;

/**
 * Created by ibf on 03/18.
 */
public class Test {
    public static final int dayOfMillis = 86400000;
    public static void main(String[] args) {
//        String str = "101:上海:25";
//        String[] arr = str.split(":");
//        System.out.println(arr.length);
//        System.out.println(Arrays.toString(arr));
//        Random random = new Random();
//        for (int i =0;i<=20;i++) {
//            getRandomTodayTimeOfMillis(random);
//        }
    }
    public static long getRandomTodayTimeOfMillis(Random random) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (random.nextDouble() <= 0.7) {
            // [0-21] => 70%
            int millis = dayOfMillis / 8 * 7;
            cal.add(Calendar.MILLISECOND, 1 + random.nextInt(millis));
            System.out.println("==70%=="+cal.getTime());
        } else {
            // [21-23] => 30%
            int millis = dayOfMillis / 24;
            cal.add(Calendar.MILLISECOND, millis*21 + random.nextInt(millis * 3));
            System.out.println("==30%=="+cal.getTime());
        }
        return cal.getTimeInMillis();

    }
}

public class FmtTest {
    public static void main(String[] args) {
        Long l = null;
        Double d = null;
        System.out.println("[%,d]".formatted(l));
        System.out.println("[%.2f]".formatted(d));
        System.out.println("[%+,d]".formatted(l));
    }
}

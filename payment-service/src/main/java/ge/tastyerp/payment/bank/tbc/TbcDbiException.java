package ge.tastyerp.payment.bank.tbc;

public class TbcDbiException extends RuntimeException {

    public static final String PASSWORD_CHANGE_REQUIRED = "TBC_PASSWORD_CHANGE_REQUIRED";
    public static final String INCORRECT_CREDENTIALS = "TBC_INCORRECT_CREDENTIALS";
    public static final String USER_IS_BLOCKED = "TBC_USER_IS_BLOCKED";

    private final String code;

    public TbcDbiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public TbcDbiException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

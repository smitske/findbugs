import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;

class DumbMethodInvocations implements Iterator {

	String f(String s) {
		return s.substring(0);
	}

	String g(String s) {
		for (int i = 0; i < s.length(); i++)
			if (s.substring(i).hashCode() == 42)
				return s;
		return null;
	}

	public boolean hasNext() {
		return next() != null;
	}

	public Object next() {
		return null;
	}

	public void remove() {
	}

	public void falsePositive() {
		Date today = Calendar.getInstance().getTime();
		System.out.println(today);
		today.setDate(16);
		System.out.println(today);
	}

	double convertToDouble(int i) {
		return Double.longBitsToDouble(i);
    }

}
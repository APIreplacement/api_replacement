package com.anon.helpers;

public class etc {
    public static String nDots(int n)
    {
        String res = "";
        while(n-- > 0)
            res += ".";
        return res;
    }
}

package dev.rikumi.flymemod;

import java.util.Arrays;

public class IconContourPixelsTest {
    private static final int W=40,H=40;
    public static void main(String[] args) {
        int[] pixels=new int[W*H];Arrays.fill(pixels,0xff707070);
        int[] flat=IconContourPixels.mask(pixels,W,H,3);
        check(red(flat,20,20)==255,"Flat interior must not glow");
        check(red(flat,0,20)<255,"Outer silhouette retains its stroke");
        for(int value:flat)check((value&0xffff)==0,"Flat luminance produces no detail highlighter");

        for (int[] textured : new int[][]{
                step(0xffffffff,0xff000000), step(0xff000000,0xffffffff),
                step(0xffff0000,0xff009400), step(0xffffffff,0xffcccccc)}) {
            check(Arrays.equals(flat,textured),"RGB texture must not affect any mask channel");
            for(int value:textured)check((value&0xffff)==0,"No detail or halo channels remain");
        }
        for(int i=0;i<pixels.length;i++)pixels[i]=0xff000000|((i*7919)&0xffffff);
        check(Arrays.equals(flat,IconContourPixels.mask(pixels,W,H,3)),"Arbitrary internal texture has no light");

        Arrays.fill(pixels,0);
        for(int y=10;y<30;y++)for(int x=10;x<30;x++)pixels[y*W+x]=0xffffffff;
        int[] isolated=IconContourPixels.mask(pixels,W,H,3);
        for(int value:isolated)check((value&0xffff)==0,"Transparent surroundings produce no internal highlight");
        pixels[20*W+20]=0;
        int[] cutout=IconContourPixels.mask(pixels,W,H,3);
        check(cutout[20*W+20]==0,"Transparent cutout stays transparent");
        check(red(cutout,21,20)<255,"Original transparent cutout contour retained");
        Arrays.fill(pixels,0x20707070);
        for(int value:IconContourPixels.mask(pixels,W,H,3))check(value==0,"Faint bitmap shadow excluded");
        check(IconContourPixels.mask(new int[]{0x3fffffff},1,1,1)[0]==0,"Alpha below 64 excluded");
        check(IconContourPixels.mask(new int[]{0x40000000},1,1,1)[0]!=0,"Alpha 64 included");
        check(IconContourPixels.mask(new int[]{0xffaaaaaa},1,1,0).length==1,"Tiny icon and zero stroke");
        System.out.println("PASS: RGB independence, no internal detail or halo, silhouette distance, transparent cutouts, alpha threshold, tiny icons");
    }
    private static int[] step(int left,int right) {
        int[] pixels=new int[W*H];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++)pixels[y*W+x]=x<20?left:right;
        return IconContourPixels.mask(pixels,W,H,3);
    }
    private static int red(int[] pixels,int x,int y){return (pixels[y*W+x]>>>16)&255;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

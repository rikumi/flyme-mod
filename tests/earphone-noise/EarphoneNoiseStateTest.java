package dev.rikumi.flymemod;
public final class EarphoneNoiseStateTest {
    static int checks;
    static void equal(int actual,int expected){checks++;if(actual!=expected)throw new AssertionError(actual+" != "+expected);}
    public static void main(String[] args){
        EarphoneNoiseState state=new EarphoneNoiseState();
        equal(state.observed("a",3),3);
        equal(state.target("a",3),2);
        state.requested("a",2);
        equal(state.observed("a",3),2);
        equal(state.target("a",3),3);
        state.requested("a",3);
        equal(state.observed("a",2),3);
        equal(state.observed("a",3),3);
        equal(state.observed("a",1),1);
        equal(state.target("a",1),3);
        state.requested("a",3);
        equal(state.observed("b",2),2);
        equal(state.target("b",2),3);
        state.requested("b",3);
        state.clear();
        equal(state.observed("b",2),2);
        for(int i=0;i<100;i++){
            int target=state.target("b",2);
            equal(target,i%2==0?3:2);
            state.requested("b",target);
        }
        System.out.println("PASS: "+checks+" asynchronous mode checks");
    }
}

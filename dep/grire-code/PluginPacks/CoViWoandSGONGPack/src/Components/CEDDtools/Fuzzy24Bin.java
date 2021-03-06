/*
 * This file is part of the LIRe project: http://www.semanticmetadata.net/lire
 * LIRe is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * LIRe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LIRe; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * We kindly ask you to refer the following paper in any publication mentioning Lire:
 *
 * Lux Mathias, Savvas A. Chatzichristofis. Lire: Lucene Image Retrieval –
 * An Extensible Java CBIR Library. In proceedings of the 16th ACM International
 * Conference on Multimedia, pp. 1085-1088, Vancouver, Canada, 2008
 *
 * http://doi.acm.org/10.1145/1459359.1459577
 *
 * Copyright statement:
 * --------------------
 * (c) 2002-2011 by Mathias Lux (mathias@juggle.at)
 *     http://www.semanticmetadata.net/lire
 */

package Components.CEDDtools;

public class Fuzzy24Bin {
    public float[] ResultsTable = new float[3];
    public float[] Fuzzy24BinHisto = new float[24];
    public boolean KeepPreviusValues = false;

    protected static float[] SaturationMembershipValues = {0, 0, 68, 188,
            68, 188, 255, 255};

    protected static float[] ValueMembershipValues = {0, 0, 68, 188,
            68, 188, 255, 255};

    //  protected static float[] ValueMembershipValues = new float[8] {  0,0,68, 188,
    //        50,138,255, 255};

    public static FuzzyRules[] Fuzzy24BinRules = new FuzzyRules[4];

    public static float[] SaturationActivation = new float[2];
    public static float[] ValueActivation = new float[2];

    public static int[][] Fuzzy24BinRulesDefinition = {
            {1, 1, 1},
            {0, 0, 2},
            {0, 1, 0},
            {1, 0, 2}
    };


    public Fuzzy24Bin(boolean KeepPreviuesValues) {
        for (int R = 0; R < 4; R++) {
            Fuzzy24BinRules[R] = new FuzzyRules();
            Fuzzy24BinRules[R].Input1 = Fuzzy24BinRulesDefinition[R][0];
            Fuzzy24BinRules[R].Input2 = Fuzzy24BinRulesDefinition[R][1];
            Fuzzy24BinRules[R].Output = Fuzzy24BinRulesDefinition[R][2];

        }

        this.KeepPreviusValues = KeepPreviuesValues;


    }

    private void FindMembershipValueForTriangles(float Input, float[] Triangles, float[] MembershipFunctionToSave) {
        int Temp = 0;

        for (int i = 0; i <= Triangles.length - 1; i += 4) {

            MembershipFunctionToSave[Temp] = 0;

            if (Input >= Triangles[i + 1] && Input <= +Triangles[i + 2]) {
                MembershipFunctionToSave[Temp] = 1;
            }

            if (Input >= Triangles[i] && Input < Triangles[i + 1]) {
                MembershipFunctionToSave[Temp] = (Input - Triangles[i]) / (Triangles[i + 1] - Triangles[i]);
            }


            if (Input > Triangles[i + 2] && Input <= Triangles[i + 3]) {
                MembershipFunctionToSave[Temp] = (Input - Triangles[i + 2]) / (Triangles[i + 2] - Triangles[i + 3]) + 1;
            }

            Temp += 1;
        }

    }

    private void LOM_Defazzificator(FuzzyRules[] Rules, float[] Input1, float[] Input2, float[] ResultTable) {
        int RuleActivation = -1;
        float LOM_MAXofMIN = 0;

        for (int i = 0; i < Rules.length; i++) {

            if ((Input1[Rules[i].Input1] > 0) && (Input2[Rules[i].Input2] > 0)) {

                float Min = 0;
                Min = Math.min(Input1[Rules[i].Input1], Input2[Rules[i].Input2]);

                if (Min > LOM_MAXofMIN) {
                    LOM_MAXofMIN = Min;
                    RuleActivation = Rules[i].Output;
                }

            }

        }


        ResultTable[RuleActivation]++;


    }

    private void MultiParticipate_Equal_Defazzificator(FuzzyRules[] Rules, float[] Input1, float[] Input2, float[] ResultTable) {

        int RuleActivation = -1;

        for (int i = 0; i < Rules.length; i++) {
            if ((Input1[Rules[i].Input1] > 0) && (Input2[Rules[i].Input2] > 0)) {
                RuleActivation = Rules[i].Output;
                ResultTable[RuleActivation]++;

            }

        }
    }

    private void MultiParticipate_Defazzificator(FuzzyRules[] Rules, float[] Input1, float[] Input2, float[] ResultTable) {

        int RuleActivation = -1;
        float Min = 0;
        for (int i = 0; i < Rules.length; i++) {
            if ((Input1[Rules[i].Input1] > 0) && (Input2[Rules[i].Input2] > 0)) {
                Min = Math.min(Input1[Rules[i].Input1], Input2[Rules[i].Input2]);

                RuleActivation = Rules[i].Output;
                ResultTable[RuleActivation] += Min;

            }

        }
    }


    public float[] ApplyFilter(float Hue, float Saturation, float Value, float[] ColorValues, int Method) {
        // Method   0 = LOM
        //          1 = Multi Equal Participate
        //          2 = Multi Participate

        ResultsTable[0] = 0;
        ResultsTable[1] = 0;
        ResultsTable[2] = 0;
        float Temp = 0;


        FindMembershipValueForTriangles(Saturation, SaturationMembershipValues, SaturationActivation);
        FindMembershipValueForTriangles(Value, ValueMembershipValues, ValueActivation);


        if (this.KeepPreviusValues == false) {
            for (int i = 0; i < 24; i++) {
                Fuzzy24BinHisto[i] = 0;
            }

        }

        for (int i = 3; i < 10; i++) {
            Temp += ColorValues[i];
        }

        if (Temp > 0) {
            if (Method == 0) LOM_Defazzificator(Fuzzy24BinRules, SaturationActivation, ValueActivation, ResultsTable);
            if (Method == 1)
                MultiParticipate_Equal_Defazzificator(Fuzzy24BinRules, SaturationActivation, ValueActivation, ResultsTable);
            if (Method == 2)
                MultiParticipate_Defazzificator(Fuzzy24BinRules, SaturationActivation, ValueActivation, ResultsTable);


        }

        for (int i = 0; i < 3; i++) {
            Fuzzy24BinHisto[i] += ColorValues[i];
        }


        for (int i = 3; i < 10; i++) {
            Fuzzy24BinHisto[(i - 2) * 3] += ColorValues[i] * ResultsTable[0];
            Fuzzy24BinHisto[(i - 2) * 3 + 1] += ColorValues[i] * ResultsTable[1];
            Fuzzy24BinHisto[(i - 2) * 3 + 2] += ColorValues[i] * ResultsTable[2];
        }

        return (Fuzzy24BinHisto);

    }


}

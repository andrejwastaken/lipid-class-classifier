import os.path
from copy import deepcopy
from enum import Enum

import matplotlib.colors as colors
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import pyopenms as oms
from pyopenms.plotting import plot_spectrum, mirror_plot_spectrum


class NormalizationMethod(Enum):
    TO_ONE: str = "to_one" # type: ignore
    TO_MAX: str = "to_TIC" # type: ignore


class TransformedMzMS:
    """
    A class to represent a transformed mass spectrometry data object.
    It contains the original mass spectrometry data and the transformed data.
    """

    data_path = ''

    def __init__(self, path: str, tag: str = None, data_path: str = ''):
        self.path = path
        self.exp_name = path.split("/")[-1].split(".")[0]
        self.transformation_log = []
        self.exp = oms.MSExperiment()
        oms.MzMLFile().load(self.path, self.exp)
        self.exp.updateRanges()
        self.out_features = None
        self.df = self.to_df()
        self.tag = tag
        self.data_path = data_path

    def store_mzml(self, out_path):
        self.exp.setMetaValue('notes', ';'.join(self.transformation_log))
        os.makedirs(f"{out_path}/{self.tag}", exist_ok=True)
        oms.MzMLFile().store(f"{out_path}/{self.tag}/{self.exp_name}.mzML", self.exp)

    def normalize(self, method: NormalizationMethod = NormalizationMethod.TO_MAX): # type: ignore
        """
        Normalize the mass spectrometry data using OpenMS.
        :param path: Path to the mass spectrometry file.

        """
        normalizer = oms.Normalizer()
        param = normalizer.getParameters()
        param.setValue("method", method.value)
        normalizer.setParameters(param)

        normalizer.filterPeakMap(self.exp)
        self.transformation_log.append(f"normalize_{method}")

    def do_calibration(self, theo_mz, tol_ppm):

        # Collect individual shifts
        spectra = self.exp.getSpectra()
        deltas = []
        ms1_specs = [s for s in spectra if s.getMSLevel() == 1]

        for spec in ms1_specs:
            mzs, ints = spec.get_peaks()
            diffs = np.abs((mzs - theo_mz) / theo_mz * 1e6)
            hits = np.where(diffs <= tol_ppm)[0]
            if len(hits):
                idx = hits[np.argmax(ints[hits])]
                shift = mzs[idx] - theo_mz
                deltas.append((spec.getRT(), shift))

        if not deltas:
            print(f"No hits found for {theo_mz} Da within {tol_ppm} ppm tolerance.")
            return 0.0

        # Optional: smooth it across RT (e.g., rolling median window)
        # For simplicity here, we use the **median shift**
        global_shift = np.median([d for rt, d in deltas])
        print(f"Applying median shift: {global_shift:.6f} Da")

        self.transformation_log.append(f"cal_{theo_mz}_ppm_{tol_ppm}_shift_{global_shift:.6f}")
        # Apply the shift to each MS¹ spectrum
        for spec in ms1_specs:
            mzs, ints = spec.get_peaks()
            spec.set_peaks((mzs - global_shift, ints))

        return global_shift

    def extract_features(self, skip_on_exist=False):

        if os.path.exists(f"{self.data_path}features/{self.tag}/{self.exp_name}.featureXML"):
            print(f"Features already extracted for {self.exp_name}.")
            if skip_on_exist:
                return
            fh = oms.FeatureXMLFile()
            self.out_features = oms.FeatureMap()
            fh.load(f"{self.data_path}features/{self.tag}/{self.exp_name}.featureXML", self.out_features)
            return

        ff = oms.FeatureFindingMetabo()
        # Run the feature finder
        out_features = oms.FeatureMap()  ## our result
        seeds = oms.FeatureMap()  ## optional: you can provide seeds where FF should take place -- not used here
        params = ff.getParameters()  ## we do not modify params for now
        params.setValue("isotope_filtering_model", "none")
        params.setValue("remove_single_traces", "true")
        params.setValue("report_convex_hulls", "true")
        ff.setParameters(params)
        ff.run(self.exp, out_features, params)

        out_features.setUniqueIds()
        self.out_features = out_features
        fh = oms.FeatureXMLFile()

        os.makedirs(f"{self.data_path}features/{self.tag}", exist_ok=True)
        fh.store(f"{self.data_path}features/{self.tag}/{self.exp_name}.featureXML", out_features)
        print("Found", out_features.size(), "features")

    def group_peaks(self, rt_tol=15, mz_tol=0.5, aggregate="sum"):
        df = self.to_df()

        df["RT"] = df["RT"].div(rt_tol).round().mul(rt_tol)
        df["mz"] = df["mz"].div(mz_tol).round().mul(mz_tol)
        df = df.groupby(["RT", "mz"]).aggregate(aggregate).reset_index()

        self.from_df(df)
        self.transformation_log.append(f"grouped_{aggregate}_rt_{rt_tol}_mz_{mz_tol}")
        # store the exp

        os.makedirs(f"{self.data_path}transformed/{self.tag}", exist_ok=True)

        oms.MzMLFile().store(
            f"{self.data_path}transformed/{self.tag}/{self.exp_name}_{'_'.join(self.transformation_log)}.mzML",
            self.exp)

    def tic_spectra(self, mz_round_decimals=0):
        df = self.to_df()
        df = df.groupby("mz").sum().reset_index().get(["mz", "inty"])
        total_inty = df["inty"].sum()
        df["inty"] = df["inty"] / total_inty

        observed_spectrum = oms.MSSpectrum()
        observed_spectrum.setRT(0)
        observed_spectrum.set_peaks((df["mz"].values, df["inty"].values))

        return observed_spectrum, df

    def plot_spectrum(self, observed_spectrum, top_n=1000):
        fig_path = f'{self.data_path}fig/{self.tag}/{self.exp_name}_sum_spectrum.png'
        if os.path.exists(fig_path):
            print(f"Figure already exists: {fig_path}")
            return fig_path
        nlargest = oms.NLargest()
        params = oms.Param()
        s2 = deepcopy(observed_spectrum)
        params.setValue("n", top_n)  # number of peaks to keep
        nlargest.setParameters(params)
        nlargest.filterSpectrum(s2)

        plt.bar(s2.get_peaks()[0], s2.get_peaks()[1], snap=False)
        os.makedirs(f"{self.data_path}fig/{self.tag}", exist_ok=True)
        plt.savefig(fig_path)
        return fig_path

    def compare_tic_spectrum(self, other, top_n=1000):
        fig_path = f'{self.data_path}fig/{self.tag}/{self.exp_name}_vs_{other.exp_name}_tic_spectrum.png'
        if os.path.exists(fig_path):
            print(f"Figure already exists: {fig_path}")
            return fig_path
        nlargest = oms.NLargest()
        params = oms.Param()
        s1, _ = self.tic_spectra()
        s2, _ = other.tic_spectra()
        params.setValue("n", top_n)  # number of peaks to keep
        nlargest.setParameters(params)
        nlargest.filterSpectrum(s1)
        nlargest.filterSpectrum(s2)

        mirror_plot_spectrum(
            s1, s2
        )
        os.makedirs(f"{self.data_path}fig/{self.tag}", exist_ok=True)

        plt.savefig(fig_path)
        return fig_path

    def visualize_features(self):
        fig_path = f'{self.data_path}fig/{self.tag}/{self.exp_name}_features.png'
        if os.path.exists(fig_path):
            print(f"Figure already exists: {fig_path}")
            return fig_path
        if self.out_features is None:
            self.extract_features()
        # reset plot
        plt.clf()
        fig = plt.figure()
        ax = fig.add_subplot(111, projection="3d")
        for f in self.out_features:
            mz, rt, inty = f.getMZ(), f.getRT(), f.getIntensity()
            # draw bar
            ax.bar3d(
                rt,
                mz,
                0,
                1,  # width
                1,  # depth
                inty,
                shade=True,
                color="blue",
            )

        os.makedirs(f"{self.data_path}fig/{self.tag}", exist_ok=True)

        plt.savefig(fig_path)
        return fig_path

    def plot_spectra_2D(self, ms_level=1, marker_size=5):
        plt.clf()
        spectra = self.exp.getSpectra()
        for spec in spectra:
            if spec.getMSLevel() == ms_level:
                mz, intensity = spec.get_peaks()
                p = intensity.argsort()  # sort by intensity to plot highest on top
                rt = np.full([mz.shape[0]], spec.getRT(), float)
                plt.scatter(
                    rt,
                    mz[p],
                    c=intensity[p],
                    cmap="afmhot_r",
                    s=marker_size,
                    norm=colors.LogNorm(
                        self.exp.getMinIntensity() + 1, self.exp.getMaxIntensity()
                    ),
                )

        plt.clim(self.exp.getMinIntensity() + 1, self.exp.getMaxIntensity())
        plt.xlabel("time (s)")
        plt.ylabel("m/z")
        plt.colorbar()

        os.makedirs(f"{self.data_path}fig/{self.tag}", exist_ok=True)
        fig_path = f'{self.data_path}fig/{self.tag}/{self.exp_name}_{"_".join(self.transformation_log)}.png'
        plt.savefig(fig_path)
        return fig_path

    def join(self, tmz):
        main = pd.merge(self.df, tmz.df, how='inner', on=['RT', 'mz'], suffixes=('', f'_{tmz.exp_name}'))
        main.sort_values(by=['RT', 'mz'], inplace=True)
        return main

    def to_df(self):
        df = self.exp.get_df(long=True)
        return df[df.ms_level == 1].get(["RT", "mz", "inty"])

    def from_df(self, df):
        exp = oms.MSExperiment()

        for rt, group in df.groupby("RT"):
            spectrum = oms.MSSpectrum()
            spectrum.setRT(rt)  # Set retention time (RT) for the spectrum
            spectrum.setMSLevel(1)  # Set MS level to 1

            # Extract all m/z and intensity values for this time point
            mz_array = np.array(group["mz"], dtype=np.float64)
            intensity_array = np.array(group["inty"], dtype=np.float64)

            # Set the peaks using the numpy arrays
            spectrum.set_peaks((mz_array, intensity_array))

            # Add the spectrum to the experiment
            exp.addSpectrum(spectrum)

        exp.updateRanges()
        self.df = df
        self.exp = exp

    def join_tic_spectra(self, other, decimals=5):
        ss, dfs = self.tic_spectra()
        os, dfos = other.tic_spectra()
        # dfs['mz'] = dfs['mz'].round(decimals)
        # dfos['mz'] = dfos['mz'].round(decimals)
        return pd.merge(dfs, dfos, how='inner', on='mz', suffixes=(f'_{self.exp_name}', f'_{other.exp_name}'))

    def filter_by_mz(self, mz_list, decimals=4):
        """
        Filter the mass spectrometry data by a list of m/z values.
        :param mz_list: List of m/z values to filter by.
        :param decimals: Number of decimal places to round m/z values.
        :return: Filtered DataFrame.
        """
        df = self.to_df()
        df[f'mz_{decimals}'] = df['mz'].mul(10 ** decimals).round()
        mz_list = [round(mz * (10 ** decimals)) for mz in mz_list]
        filtered_df = df[df[f'mz_{decimals}'].isin(mz_list)].reset_index(drop=True)
        return filtered_df.get(["RT", "mz", "inty"])

    # peptide search functions
    def search_peptides(self, fasta_path: str, search_params: dict = None):
        """
                Search for peptides using SimpleSearchEngineAlgorithm.

                :param fasta_path: Path to FASTA database file
                :param search_params: Dictionary of search parameters
                :return: Tuple of (protein_ids, peptide_ids)
        """

        print("Searching peptides in", fasta_path)

        # if no search_params provided use these
        if search_params is None:
            search_params = {
                "precursor:mass_tolerance": 10.0,  # ppm
                "fragment:mass_tolerance": 0.3,  # Da
                "peptide:max_size": 30,
                "annotate:PSM": ["fragment_mz_error_median_ppm", "precursor_mz_error_ppm"]
            }

        search_engine = oms.SimpleSearchEngineAlgorithm()
        params = search_engine.getDefaults()

        # Set custom parameters
        for key, value in search_params.items():
            params.setValue(key.encode(), value)

        search_engine.setParameters(params)

        protein_ids = []
        peptide_ids = []

        # Save current experiment to temporary file for search
        temp_mzml = f"temp_{self.exp_name}.mzML"
        oms.MzMLFile().store(temp_mzml, self.exp)

        search_engine.search(temp_mzml, fasta_path, protein_ids, peptide_ids)

        # remove temp file
        os.remove(temp_mzml)

        self.transformation_log.append(f"peptide_search_{len(peptide_ids)}_PSMs")
        return protein_ids, peptide_ids

    def apply_fdr_filtering(self, protein_ids, peptide_ids, psm_fdr: float = 0.01):
        """
        Apply FDR filtering to peptide identifications.

        :param protein_ids: List of protein identifications
        :param peptide_ids: List of peptide identifications
        :param psm_fdr: FDR threshold (default 1%)
        :return: Filtered (protein_ids, peptide_ids)
        """

        # Calculate q-values
        oms.FalseDiscoveryRate().apply(peptide_ids)

        # Filter by FDR threshold
        idfilter = oms.IDFilter()
        idfilter.filterHitsByScore(peptide_ids, psm_fdr)
        idfilter.removeDecoyHits(peptide_ids)

        self.transformation_log.append(f"FDR_filtered_{psm_fdr}")
        return protein_ids, peptide_ids
